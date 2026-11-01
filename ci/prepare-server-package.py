#!/usr/bin/env python3
"""Finalize a fresh Karaf assembly with exact bundle provenance and payload hashes."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile

FIELDS = ('sourceRevision', 'sourceDirty', 'protocolRevision', 'protocolDirty')
HEADERS = ('DSP408-Source-Revision', 'DSP408-Source-Dirty',
           'DSP408-Protocol-Revision', 'DSP408-Protocol-Dirty')


def prepare(assembly, version, karaf_version='4.4.11'):
    assembly = Path(assembly).resolve()
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,70}', version):
        raise ValueError('Invalid package version')
    provenance = None
    for artifact in ('api', 'core', 'runtime', 'http'):
        jars = list(assembly.glob(f'system/de/drremote/dsp408-{artifact}/*/dsp408-{artifact}-*.jar'))
        if len(jars) != 1:
            raise ValueError('Expected one packaged Controller bundle: ' + artifact)
        with zipfile.ZipFile(jars[0]) as jar:
            manifest = jar.read('META-INF/MANIFEST.MF').decode().replace('\r\n ', '')
        headers = dict(line.split(': ', 1) for line in manifest.splitlines() if ': ' in line)
        current = dict(zip(FIELDS, (headers.get(header, 'unknown') for header in HEADERS)))
        if provenance is not None and provenance != current:
            raise ValueError('Controller bundle provenance does not match: ' + artifact)
        provenance = current
    for key in ('sourceRevision', 'protocolRevision'):
        if provenance[key] != 'unknown' and not re.fullmatch('[0-9a-f]{40}', provenance[key]):
            raise ValueError('Invalid bundle revision: ' + key)
    for key in ('sourceDirty', 'protocolDirty'):
        if provenance[key] not in ('true', 'false', 'unknown'):
            raise ValueError('Invalid bundle dirty flag: ' + key)
    for path in assembly.rglob('*'):
        rel = path.relative_to(assembly)
        if path.is_symlink():
            raise ValueError('Symlink in release payload: ' + str(rel))
        if not path.is_file():
            continue
        if (rel.parts[0] == 'data' and rel.as_posix() != 'data/tmp/README') or path.name in ('device-registry.json', 'host.key', 'known_hosts') or path.suffix == '.log':
            raise ValueError('Live runtime material in release payload: ' + str(rel))
        if rel.as_posix() == 'etc/users.properties':
            for line in path.read_text().splitlines():
                line = line.strip()
                if line and not line.startswith(('#', '!', '_g_')):
                    raise ValueError('Enabled account in release payload')
    release_id = version + '-' + provenance['sourceRevision'][:12]
    if provenance['sourceDirty'] != 'false':
        release_id += '-dirty'
    metadata = dict(schemaVersion=1, releaseId=release_id, version=version,
                    **provenance, karafVersion=karaf_version, javaMajor=21)
    (assembly / 'release.json').write_text(json.dumps(metadata, indent=2) + '\n')
    files = {p.relative_to(assembly).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
             for p in sorted(assembly.rglob('*')) if p.is_file() and p != assembly / 'SHA256SUMS.json'}
    (assembly / 'SHA256SUMS.json').write_text(json.dumps({'schemaVersion': 1, 'files': files}, indent=2) + '\n')
    return metadata


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--assembly', type=Path, required=True)
    parser.add_argument('--version', required=True)
    parser.add_argument('--karaf-version', default='4.4.11')
    args = parser.parse_args()
    print(json.dumps(prepare(args.assembly, args.version, args.karaf_version), indent=2))


if __name__ == '__main__':
    main()
