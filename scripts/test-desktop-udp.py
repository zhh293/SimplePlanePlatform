#!/usr/bin/env python3
"""Run the SDD UDP checks, including production TUN packet injection (no admin required)."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--offline', action='store_true')
    parser.add_argument('--maven', default=shutil.which('mvn') or shutil.which('mvn.cmd'))
    parser.add_argument('--test-jvm', help='Optional JDK 17+ test java executable')
    parser.add_argument('--maven-repo', help='Optional Maven repository directory')
    args = parser.parse_args()
    if not args.maven:
        parser.error('Maven is required; use --maven if it is not on PATH')
    cargo = shutil.which('cargo')
    if not cargo:
        parser.error('cargo is required on PATH')
    offline = ['--offline'] if args.offline else []
    subprocess.run([cargo, 'test', '--manifest-path', 'tun-adapter/Cargo.toml', '--locked', *offline], cwd=ROOT, check=True)
    subprocess.run([cargo, 'build', '--manifest-path', 'tun-adapter/Cargo.toml', '--locked', '--example', 'udp_probe', *offline], cwd=ROOT, check=True)
    target = Path(os.environ.get('CARGO_TARGET_DIR', ROOT / 'tun-adapter/target'))
    if not target.is_absolute():
        target = ROOT / target
    probe = target / 'debug/examples' / ('udp_probe.exe' if os.name == 'nt' else 'udp_probe')
    command = [args.maven, '-B', 'test', '-pl', 'proxy-local,proxy-remote', '-am', f'-Dproxy.udp.tunProbe={probe}']
    if args.offline:
        command.append('-o')
    if args.maven_repo:
        command.append(f'-Dmaven.repo.local={args.maven_repo}')
    if args.test_jvm:
        command.append(f'-Djvm={args.test_jvm}')
        modules = subprocess.check_output([args.test_jvm, '--list-modules'], text=True)
        if 'java.instrument@' not in modules:
            print('Test runtime lacks java.instrument: JaCoCo disabled; all tests remain enabled.', flush=True)
            command.append('-Djacoco.skip=true')
    subprocess.run(command, cwd=ROOT, check=True)


if __name__ == '__main__':
    try:
        main()
    except subprocess.CalledProcessError as error:
        sys.exit(error.returncode)
