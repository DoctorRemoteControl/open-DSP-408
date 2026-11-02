#!/usr/bin/env python3
"""Verify and exec the native Karaf server with external, locked state."""
import sys
sys.dont_write_bytecode = True
import argparse
import errno
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import time
from common import (DeploymentError, atomic_write, check_layout, ensure_directory, load_settings,
                    lock_file, ordinary_file, prepare_state, safe_path, verify_release)


def properties(path, values):
    def escape(value):
        return str(value).replace('\\', '\\\\').replace('\n', '\\n').replace('\r', '\\r').replace(' ', '\\ ')
    atomic_write(path, ''.join(str(key) + '=' + escape(value) + '\n' for key, value in values.items()))


def clean_environment():
    return {key: value for key, value in os.environ.items()
            if not key.startswith(('KARAF_', 'JAVA_', 'JDK_', 'JRE_', 'JAVACMD', 'CLASSPATH', 'LD_PRELOAD', 'LD_LIBRARY_PATH'))
            and key not in ('_JAVA_OPTIONS', 'JAVA_HOME', 'JAVA', 'DISPLAY', 'WAYLAND_DISPLAY', 'XAUTHORITY',
                            'EXTRA_JAVA_OPTS', 'SSH_AUTH_SOCK', 'SSH_AGENT_PID')}


def java_environment(env):
    java = shutil.which('java', path=env.get('PATH'))
    if not java:
        raise DeploymentError('Java 21 or newer is required (java not found in PATH)')
    java = str(Path(java).resolve())
    try:
        result = subprocess.run([java, '-version'], env=env, text=True, capture_output=True, timeout=15)
    except (OSError, subprocess.SubprocessError) as exc:
        raise DeploymentError('Cannot check Java 21 requirement: ' + str(exc)) from exc
    match = re.search(r'(?:openjdk|java)\s+version\s+"?(\d+)(?:[.\s"-]|$)', result.stderr + result.stdout)
    if result.returncode != 0 or not match or int(match.group(1)) < 21:
        raise DeploymentError('Java 21 or newer is required')
    env.update(JAVA=java, JAVA_HOME=str(Path(java).parent.parent), JAVA_OPTS='-Djava.awt.headless=true',
               EXTRA_JAVA_OPTS='-Djava.awt.headless=true')


def wait_for_listeners(addresses):
    # A user service can start before DHCP assigns its configured LAN address.
    # Pax Web otherwise keeps the JVM alive after a failed multi-address bind.
    deadline = time.monotonic() + 30
    announced = False
    while True:
        try:
            for address in addresses:
                family = socket.AF_INET6 if ':' in address else socket.AF_INET
                with socket.socket(family, socket.SOCK_STREAM) as probe:
                    probe.bind((address, 0))
            return
        except OSError as exc:
            if exc.errno != errno.EADDRNOTAVAIL:
                raise
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise DeploymentError('Configured listen address is unavailable: ' + address) from exc
            if not announced:
                print('dsp408-server: waiting for configured listen addresses', file=sys.stderr, flush=True)
                announced = True
            time.sleep(min(.25, remaining))


def prepare_runtime(release, state, meta, settings):
    for current, directories, files in os.walk(state / 'log', followlinks=False):
        for name in directories + files:
            path = safe_path(Path(current) / name)
            if not path.is_file() and not path.is_dir():
                raise DeploymentError(f'Log directory contains a special file: {path}')
    runtime = ensure_directory(state / 'runtimes' / meta['releaseId'])
    for current, directories, files in os.walk(runtime, followlinks=False):
        for name in directories + files:
            path = safe_path(Path(current) / name)
            if not path.is_file() and not path.is_dir():
                raise DeploymentError(f'Runtime contains a special file: {path}')
    data = ensure_directory(runtime / 'data')
    ensure_directory(runtime / 'instances')
    etc = safe_path(runtime / 'etc')
    # Never follow links left in a writable runtime tree when replacing configuration.
    if etc.exists():
        for current, directories, files in os.walk(etc):
            for name in directories + files:
                safe_path(Path(current) / name)
        shutil.rmtree(etc)
    shutil.copytree(release / 'etc', etc)
    for pid in ('de.drremote.dsp408controller.config',):
        conflict = ordinary_file(etc / pid)
        if conflict.exists():
            conflict.unlink()
    properties(etc / 'de.drremote.dsp408controller.cfg', {
        'registry.mode': 'managed', 'registry.file': state / 'device-registry.json',
        'auto.connect': 'false', 'meter.poll.interval.ms': settings['meterPollIntervalMs']})
    properties(etc / 'org.ops4j.pax.web.cfg', {
        'org.osgi.service.http.port': settings['httpPort'],
        'org.ops4j.pax.web.listening.addresses': ','.join(settings['listenAddresses'])})
    properties(etc / 'de.drremote.dsp408.web.cfg', {'enabled': str(settings.get('webEnabled', False)).lower()})
    properties(etc / 'org.ops4j.pax.url.mvn.cfg', {
        'org.ops4j.pax.url.mvn.offline': 'true',
        'org.ops4j.pax.url.mvn.localRepository': ensure_directory(runtime / 'maven-cache'),
        'org.ops4j.pax.url.mvn.settings': runtime / 'empty-settings.xml',
        'org.ops4j.pax.url.mvn.useFallbackRepositories': 'false',
        'org.ops4j.pax.url.mvn.defaultRepositories': (release / 'system').as_uri() + '@id=bundled@snapshots',
        'org.ops4j.pax.url.mvn.repositories': ''})
    atomic_write(runtime / 'empty-settings.xml', '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"/>\n')
    return runtime, etc, data


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--state-dir', required=True, type=Path)
    args = parser.parse_args()
    descriptor = None
    try:
        release = Path(__file__).resolve().parents[1]
        meta = verify_release(release)
        state = safe_path(args.state_dir)
        if state == release or state in release.parents or release in state.parents:
            raise DeploymentError('State and release paths overlap')
        # An installed release must also stay separate from its installation root.
        if release.parent.name == 'releases':
            check_layout(release.parent.parent, state)
        ensure_directory(state)
        ensure_directory(state / 'run')
        descriptor = lock_file(state / 'run/server.lock', 'Server')
        prepare_state(state)
        settings = load_settings(state)
        env = clean_environment()
        java_environment(env)
        wait_for_listeners(settings['listenAddresses'])
        runtime, etc, data = prepare_runtime(release, state, meta, settings)
        env.update(KARAF_HOME=str(release), KARAF_BASE=str(runtime), KARAF_ETC=str(etc),
                   KARAF_DATA=str(data), KARAF_LOG=str(state / 'log'), KARAF_EXEC='exec',
                   # Native Karaf changes cwd to KARAF_BASE before launching Java.
                   # A fixed relative directory survives spaces in operator paths.
                   KARAF_OPTS='-Dkaraf.instances=instances',
                   DSP408_LOCK_FD=str(descriptor))
        os.set_inheritable(descriptor, True)
        os.execve(release / 'bin/karaf', [str(release / 'bin/karaf'), 'server'], env)
    except (DeploymentError, OSError) as exc:
        print('dsp408-server: ' + str(exc), file=sys.stderr)
        return 1
    finally:
        if descriptor is not None:
            os.close(descriptor)


if __name__ == '__main__':
    sys.exit(main())
