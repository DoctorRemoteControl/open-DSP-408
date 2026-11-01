#!/usr/bin/env python3
"""Install immutable releases, roll back, or print a systemd service unit."""
import sys
sys.dont_write_bytecode = True
import argparse
from pathlib import Path
import re
from common import DeploymentError, check_layout, install_release, rollback_release


def systemd_quote(value, expand_environment=False):
    value = str(value).replace('\\', '\\\\').replace('"', '\\"').replace('%', '%%')
    if expand_environment:
        value = value.replace('$', '$$')
    return '"' + value + '"'


def service_unit(install, state, scope, user=None):
    install, state = check_layout(install, state)
    if scope == 'system' and (not user or user == 'root' or not re.fullmatch(r'[a-z_][a-z0-9_-]*\$?', user)):
        raise DeploymentError('System service requires an explicit non-root --user')
    command = ' '.join(systemd_quote(value, True) for value in
                       ('/usr/bin/python3', install / 'current/server/run.py', '--state-dir', state))
    identity = 'User=' + user + '\n' if scope == 'system' else ''
    target = 'default.target' if scope == 'user' else 'multi-user.target'
    return ('[Unit]\nDescription=DSP408 headless API server\nAfter=network.target\n'
            'StartLimitIntervalSec=60\nStartLimitBurst=5\n\n[Service]\nType=simple\n' + identity
            + 'ExecStart=' + command + '\nRestart=on-failure\nRestartSec=5\nTimeoutStopSec=30\n'
            'KillMode=control-group\nUMask=0077\n\n[Install]\nWantedBy=' + target + '\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    for name in ('install', 'rollback', 'service-file'):
        command = commands.add_parser(name)
        command.add_argument('--install-dir', required=True, type=Path)
        command.add_argument('--state-dir', required=True, type=Path)
        if name == 'install':
            command.add_argument('--allow-development-build', action='store_true')
        if name == 'service-file':
            command.add_argument('--scope', choices=('user', 'system'), required=True)
            command.add_argument('--user')
    args = parser.parse_args()
    try:
        if args.command == 'install':
            meta = install_release(Path(__file__).resolve().parents[1], args.install_dir, args.state_dir,
                                   args.allow_development_build)
            print('Installed ' + meta['releaseId'])
        elif args.command == 'rollback':
            rollback_release(args.install_dir, args.state_dir)
            print('Selected previous release')
        else:
            print(service_unit(args.install_dir, args.state_dir, args.scope, args.user), end='')
        return 0
    except (DeploymentError, OSError) as exc:
        print('dsp408ctl: ' + str(exc), file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
