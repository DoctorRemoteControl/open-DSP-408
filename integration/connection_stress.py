#!/usr/bin/env python3
"""Compile and run the loopback-only Controller lifecycle/fault stress harness."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import signal


def run_owned(command, timeout):
    child = subprocess.Popen(command)
    try:
        return child.wait(timeout=timeout)
    finally:
        if child.poll() is None:
            child.terminate()
            try:
                child.wait(timeout=10)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait(timeout=10)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cycles', type=int, default=100)
    parser.add_argument('--state-dir', type=Path, required=True)
    parser.add_argument('--maven-repo', type=Path, default=Path.home()/'.m2/repository')
    parser.add_argument('--classes', action='store_true', help='Copy compiled classes instead of packaged JARs')
    args = parser.parse_args()
    if args.cycles < 1:
        parser.error('Cycles must be positive')
    root = Path(__file__).resolve().parent.parent
    protocol = Path(os.getenv('DSP408_PROTOCOL_DIR', str(root.parent/'dsp408-protocol')))
    args.state_dir.mkdir(parents=True, exist_ok=False)
    inputs = [root/'dsp408-parent/dsp408-core/target'/('classes' if args.classes else 'dsp408-core-0.0.1-SNAPSHOT.jar'),
              protocol/'target'/('classes' if args.classes else 'dsp408-protocol-0.1.0-SNAPSHOT.jar')]
    inputs += [args.maven_repo/'com/fasterxml/jackson/core'/name/'2.17.2'/f'{name}-2.17.2.jar'
               for name in ['jackson-annotations', 'jackson-core', 'jackson-databind']]
    copied = args.state_dir/'artifacts'
    copied.mkdir()
    classpath = []
    hashes = {}
    for index, source in enumerate(inputs):
        destination = copied/f'{index}-{source.name}'
        if source.is_dir():
            shutil.copytree(source, destination)
            for path in sorted(destination.rglob('*')):
                if path.is_file():
                    hashes[str(path.relative_to(copied))] = hashlib.sha256(path.read_bytes()).hexdigest()
        else:
            shutil.copy2(source, destination)
            hashes[destination.name] = hashlib.sha256(destination.read_bytes()).hexdigest()
        classpath.append(str(destination))
    (args.state_dir/'artifact-hashes.json').write_text(json.dumps(hashes, indent=2))
    classes = args.state_dir/'harness-classes'
    classes.mkdir()
    classpath = os.pathsep.join(classpath)
    compile_command = ['javac', '--release', '21', '-cp', classpath, '-d', str(classes),
                       str(root/'integration/ConnectionStress.java')]
    compile_code = run_owned(compile_command, timeout=60)
    if compile_code:
        raise subprocess.CalledProcessError(compile_code, compile_command)
    return run_owned(['java', '-cp', str(classes)+os.pathsep+classpath, 'ConnectionStress',
                      str(args.cycles), str(args.state_dir)], timeout=max(180, args.cycles*2))

if __name__ == '__main__':
    def interrupted(signum, frame):
        raise KeyboardInterrupt(f'Validation interrupted by signal {signum}')
    signal.signal(signal.SIGTERM, interrupted)
    raise SystemExit(main())
