"""Integrity, persistent state and atomic release selection for DSP408."""
import fcntl
import hashlib
import ipaddress
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import tempfile


class DeploymentError(Exception):
    pass


ID = re.compile(r'[A-Za-z0-9][A-Za-z0-9._-]{0,159}\Z')
SHA = re.compile(r'[0-9a-f]{64}\Z')
REV = re.compile(r'(?:[0-9a-f]{40}|unknown)\Z')
DEFAULT_SETTINGS = {'schemaVersion': 1, 'listenAddresses': ['127.0.0.1'],
                    'httpPort': 8181, 'meterPollIntervalMs': 200}


def read_json(path, label):
    try:
        return json.loads(ordinary_file(path).read_text(encoding='utf-8'))
    except (OSError, ValueError) as exc:
        raise DeploymentError(f'{label}: cannot read {path}: {exc}') from exc


def safe_path(value):
    path = Path(value)
    if not path.is_absolute() or '..' in path.parts or any(c in str(path) for c in '\n\r\x00'):
        raise DeploymentError('Paths must be absolute without traversal or line breaks')
    for part in (path, *path.parents):
        if part.is_symlink():
            raise DeploymentError(f'Unsafe symlink: {part}')
    return path


def ensure_directory(path):
    safe_path(path)
    path.mkdir(parents=True, exist_ok=True)
    if not path.is_dir():
        raise DeploymentError(f'Expected directory: {path}')
    return path


def ordinary_file(path):
    safe_path(path)
    if path.exists() and not path.is_file():
        raise DeploymentError(f'Expected regular file: {path}')
    return path


def check_layout(install, state, source=None):
    install, state = safe_path(install), safe_path(state)
    paths = [install, state]
    if source is not None:
        paths.append(safe_path(source))
    for index, first in enumerate(paths):
        for second in paths[index + 1:]:
            if first == second or first in second.parents or second in first.parents:
                raise DeploymentError(f'Install, state and source paths overlap: {first}, {second}')
    return install, state


def verify_release(root):
    root = safe_path(root)
    manifest = read_json(root / 'SHA256SUMS.json', 'integrity')
    if not isinstance(manifest, dict) or type(manifest.get('schemaVersion')) is not int or manifest['schemaVersion'] != 1:
        raise DeploymentError('Integrity manifest has unsupported schema')
    files = manifest.get('files')
    if not isinstance(files, dict) or not files:
        raise DeploymentError('Integrity manifest has no files')
    for name, digest in files.items():
        path = PurePosixPath(name)
        if (not name or path.is_absolute() or '..' in path.parts or str(path) != name
                or '\\' in name or name == 'SHA256SUMS.json' or not isinstance(digest, str) or not SHA.fullmatch(digest)):
            raise DeploymentError(f'Integrity manifest contains unsafe path or hash: {name}')
    actual = set()
    for current, directories, names in os.walk(root, followlinks=False):
        for name in directories + names:
            path = Path(current) / name
            if path.is_symlink():
                raise DeploymentError(f'Integrity forbids payload symlinks: {path}')
            if path.is_dir():
                continue
            if not path.is_file():
                raise DeploymentError(f'Integrity forbids special files: {path}')
            relative = path.relative_to(root).as_posix()
            if relative != 'SHA256SUMS.json':
                actual.add(relative)
    if actual != set(files):
        raise DeploymentError('Integrity file list differs from payload (missing or extra files)')
    for name, digest in files.items():
        with (root / name).open('rb') as stream:
            calculated = hashlib.file_digest(stream, 'sha256').hexdigest() if hasattr(hashlib, 'file_digest') else _sha256(stream)
        if calculated != digest:
            raise DeploymentError(f'Integrity checksum mismatch: {name}')
    meta = read_json(root / 'release.json', 'integrity release metadata')
    if (not isinstance(meta, dict) or type(meta.get('schemaVersion')) is not int or meta['schemaVersion'] != 1
            or not isinstance(meta.get('releaseId'), str) or not ID.fullmatch(meta['releaseId'])
            or meta['releaseId'] in ('.', '..') or not isinstance(meta.get('version'), str)
            or meta.get('karafVersion') != '4.4.11' or type(meta.get('javaMajor')) is not int or meta['javaMajor'] != 21):
        raise DeploymentError('Integrity release metadata is invalid')
    for name in ('sourceRevision', 'protocolRevision'):
        if not isinstance(meta.get(name), str) or not REV.fullmatch(meta[name]):
            raise DeploymentError(f'Integrity release metadata: invalid {name}')
    for name in ('sourceDirty', 'protocolDirty'):
        if meta.get(name) not in ('true', 'false', 'unknown'):
            raise DeploymentError(f'Integrity release metadata: invalid {name}')
    expected_id = meta['version'] + '-' + meta['sourceRevision'][:12] + ('-dirty' if meta['sourceDirty'] != 'false' else '')
    if meta['releaseId'] != expected_id:
        raise DeploymentError('Integrity releaseId does not match provenance')
    for required in ('bin/karaf', 'release.json', 'server/run.py', 'server/dsp408ctl.py', 'server/common.py'):
        if required not in files:
            raise DeploymentError(f'Integrity payload lacks {required}')
    if not os.access(root / 'bin/karaf', os.X_OK):
        raise DeploymentError('Integrity launcher is not executable')
    return meta


def _sha256(stream):
    digest = hashlib.sha256()
    for block in iter(lambda: stream.read(1024 * 1024), b''):
        digest.update(block)
    return digest.hexdigest()


def atomic_write(path, content):
    ordinary_file(path)
    fd, temporary = tempfile.mkstemp(prefix='.' + path.name + '-', dir=path.parent)
    try:
        with os.fdopen(fd, 'w', encoding='utf-8') as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.lexists(temporary):
            os.unlink(temporary)


def prepare_state(state):
    ensure_directory(state)
    for name in ('config', 'run', 'runtimes', 'log'):
        ensure_directory(state / name)
    ordinary_file(state / 'device-registry.json')
    settings = ordinary_file(state / 'config/server.json')
    if not settings.exists():
        atomic_write(settings, json.dumps(DEFAULT_SETTINGS, indent=2) + '\n')


def lock_file(path, label):
    ordinary_file(path)
    descriptor = os.open(path, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    try:
        fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError as exc:
        os.close(descriptor)
        raise DeploymentError(f'{label} already running') from exc
    return descriptor


def pointer(install, name):
    path = install / name
    if not os.path.lexists(path):
        return None
    if not path.is_symlink():
        raise DeploymentError(f'Expected relative release symlink: {path}')
    target = os.readlink(path)
    parts = PurePosixPath(target).parts
    if len(parts) != 2 or parts[0] != 'releases' or not ID.fullmatch(parts[1]) or target != '/'.join(parts):
        raise DeploymentError(f'Unsafe release symlink: {path}')
    destination = safe_path(install / target)
    if not destination.is_dir():
        raise DeploymentError(f'Broken release symlink: {path}')
    return target


def _set_pointer(install, name, target):
    path = install / name
    if target is None:
        if os.path.lexists(path):
            path.unlink()
        return
    temporary = install / ('.' + name + '-' + next(tempfile._get_candidate_names()))
    try:
        temporary.symlink_to(target, target_is_directory=True)
        os.replace(temporary, path)
    finally:
        if os.path.lexists(temporary):
            temporary.unlink()


def recover_selection(install):
    journal = install / '.selection.json'
    if not os.path.lexists(journal):
        return
    record = read_json(journal, 'release selection journal')
    fields = {'schemaVersion', 'oldCurrent', 'oldPrevious', 'newCurrent', 'newPrevious'}
    if (not isinstance(record, dict) or set(record) != fields
            or type(record['schemaVersion']) is not int or record['schemaVersion'] != 1):
        raise DeploymentError('Invalid release selection journal')
    for name in fields - {'schemaVersion'}:
        target = record[name]
        if target is None:
            continue
        if not isinstance(target, str):
            raise DeploymentError('Invalid journal release target')
        parts = PurePosixPath(target).parts
        if (len(parts) != 2 or parts[0] != 'releases' or not ID.fullmatch(parts[1])
                or target != '/'.join(parts) or not safe_path(install / target).is_dir()):
            raise DeploymentError('Unsafe or missing journal release target')
    current, previous = pointer(install, 'current'), pointer(install, 'previous')
    if (current not in (record['oldCurrent'], record['newCurrent'])
            or previous not in (record['oldPrevious'], record['newPrevious'])):
        raise DeploymentError('Release pointers changed outside interrupted selection')
    committed = current == record['newCurrent'] and previous == record['newPrevious']
    if not committed:
        # A process can die between the two atomic pointer replacements. Restore
        # the complete old pair before a subsequent install/rollback observes it.
        for name, observed in (('previous', previous), ('current', current)):
            old = record['old' + name.capitalize()]
            if observed != old:
                _set_pointer(install, name, old)
    journal.unlink()


def select_release(install, current, previous):
    journal = install / '.selection.json'
    record = dict(schemaVersion=1, oldCurrent=pointer(install, 'current'),
                  oldPrevious=pointer(install, 'previous'), newCurrent=current, newPrevious=previous)
    atomic_write(journal, json.dumps(record, indent=2) + '\n')
    try:
        _set_pointer(install, 'previous', previous)
        _set_pointer(install, 'current', current)
    except OSError:
        recover_selection(install)
        raise
    # Current replacement commits the selected release. A cleanup failure must
    # not report an uncommitted install; the next locked operation finishes it.
    try:
        journal.unlink()
    except OSError:
        pass


def install_release(source, install, state, allow_development=False):
    source = safe_path(source)
    install, state = check_layout(install, state, source)
    meta = verify_release(source)
    if not allow_development and (meta['sourceDirty'] != 'false' or meta['protocolDirty'] != 'false'
                                  or 'unknown' in (meta['sourceRevision'], meta['protocolRevision'])):
        raise DeploymentError('Development provenance requires --allow-development-build')
    ensure_directory(install)
    ensure_directory(install / 'releases')
    descriptor = lock_file(install / '.install.lock', 'Installation')
    try:
        recover_selection(install)
        old_current = pointer(install, 'current')
        pointer(install, 'previous')
        target = 'releases/' + meta['releaseId']
        destination = safe_path(install / target)
        if destination.exists():
            verify_release(destination)
            if (destination / 'SHA256SUMS.json').read_bytes() != (source / 'SHA256SUMS.json').read_bytes():
                raise DeploymentError('Existing release differs; releases are never overwritten')
        else:
            temporary = Path(tempfile.mkdtemp(prefix='.install-', dir=install / 'releases'))
            try:
                shutil.copytree(source, temporary, dirs_exist_ok=True, symlinks=True)
                verify_release(temporary)
                os.rename(temporary, destination)
            finally:
                if temporary.exists():
                    shutil.rmtree(temporary)
        prepare_state(state)
        if old_current != target:
            select_release(install, target, old_current)
        return meta
    finally:
        os.close(descriptor)


def rollback_release(install, state):
    install, state = check_layout(install, state)
    ensure_directory(install / 'releases')
    descriptor = lock_file(install / '.install.lock', 'Installation')
    try:
        recover_selection(install)
        current, previous = pointer(install, 'current'), pointer(install, 'previous')
        if not current or not previous:
            raise DeploymentError('Rollback requires current and previous releases')
        verify_release(install / previous)
        select_release(install, previous, current)
    finally:
        os.close(descriptor)


def load_settings(state):
    settings = read_json(ordinary_file(state / 'config/server.json'), 'settings')
    if not isinstance(settings, dict) or type(settings.get('schemaVersion')) is not int or settings['schemaVersion'] != 1:
        raise DeploymentError('Settings schema is invalid')
    if type(settings.get('httpPort')) is not int or not 1 <= settings['httpPort'] <= 65535:
        raise DeploymentError('Settings httpPort must be an integer from 1 to 65535')
    if type(settings.get('meterPollIntervalMs')) is not int or not 20 <= settings['meterPollIntervalMs'] <= 60000:
        raise DeploymentError('Settings meterPollIntervalMs must be an integer from 20 to 60000')
    addresses = settings.get('listenAddresses')
    if not isinstance(addresses, list) or not addresses:
        raise DeploymentError('Settings listenAddresses must contain IP addresses')
    try:
        for address in addresses:
            if not isinstance(address, str) or '%' in address:
                raise ValueError('expected literal IP')
            ipaddress.ip_address(address)
    except ValueError as exc:
        raise DeploymentError('Settings listenAddresses must contain literal IP addresses') from exc
    return settings
