"""Prepare official binaries and isolated fixtures from WSL. Secrets stay in .build."""
import hashlib
import io
import json
import os
from pathlib import Path
import secrets
import shlex
import shutil
import subprocess
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parents[2]
RUN = ROOT / '.build/proxy-comparison'
REMOTE = '/home/ec2-user/simpleplane-perf-20261030'
SSH = ['ssh', '-i', '/tmp/simpleplane-perf-ladder.pem', '-o', 'BatchMode=yes',
       '-o', 'ConnectTimeout=12', '-o', 'StrictHostKeyChecking=yes',
       '-o', 'UserKnownHostsFile=' + str(ROOT / '.build/remote-node-test/known_hosts'),
       'ec2-user@54.172.101.190']


def ssh(cmd, data=None, timeout=60):
    if not Path('/tmp/simpleplane-perf-ladder.pem').exists():
        shutil.copyfile(ROOT / 'ladder.pem', '/tmp/simpleplane-perf-ladder.pem')
        os.chmod('/tmp/simpleplane-perf-ladder.pem', 0o600)
    r = subprocess.run(SSH + [cmd], input=data, capture_output=True, timeout=timeout)
    if r.returncode:
        raise RuntimeError(r.stderr.decode(errors='replace')[-3000:] + r.stdout.decode(errors='replace')[-1000:])
    return r.stdout


def remote(action, name=None):
    return ssh('sudo -n python3 ' + REMOTE + '/remote_fixture.py ' + action + (' ' + name if name else ''))


def main():
    RUN.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(ROOT / 'ladder.pem', '/tmp/simpleplane-perf-ladder.pem')
    os.chmod('/tmp/simpleplane-perf-ladder.pem', 0o600)
    archive = ROOT / '.build/ss-rust.tar.xz'
    checksum = (ROOT / '.build/ss-rust.sha256').read_text().split()[0]
    assert hashlib.sha256(archive.read_bytes()).hexdigest() == checksum
    release = json.loads((ROOT / '.build/hysteria-release.json').read_text())
    asset = next(a for a in release['assets'] if a['name'] == 'hysteria-linux-amd64')
    hysteria = ROOT / '.build/hysteria-linux-amd64'
    assert asset['digest'] == 'sha256:' + hashlib.sha256(hysteria.read_bytes()).hexdigest()
    with tarfile.open(archive) as tar:
        for name in ['ssserver', 'sslocal']:
            member = next(m for m in tar.getmembers() if Path(m.name).name == name)
            assert member.isfile()
            (RUN / name).write_bytes(tar.extractfile(member).read())
            (RUN / name).chmod(0o755)
    shutil.copyfile(hysteria, RUN / 'hysteria')
    (RUN / 'hysteria').chmod(0o755)
    password = secrets.token_urlsafe(32)
    key = secrets.token_hex(16)
    (RUN / 'credentials.private.json').write_text(json.dumps({'password': password, 'key': key}))
    os.chmod(RUN / 'credentials.private.json', 0o600)
    logback = '<configuration><appender name="C" class="ch.qos.logback.core.ConsoleAppender"><encoder><pattern>%level %logger - %msg%n</pattern></encoder></appender><root level="WARN"><appender-ref ref="C"/></root></configuration>'
    files = {
        'remote_fixture.py': (ROOT / 'scripts/perf/remote_fixture.py').read_bytes(),
        'ssserver': (RUN / 'ssserver').read_bytes(),
        'hysteria': hysteria.read_bytes(),
        'logback.xml': logback.encode(),
        'ss-server.private.json': json.dumps({'server': '0.0.0.0', 'server_port': 18443,
            'password': password, 'method': 'chacha20-ietf-poly1305', 'mode': 'tcp_only',
            'timeout': 60, 'no_delay': True, 'runtime': {'mode': 'multi_thread', 'worker_count': 2}}).encode(),
        'hy-server.private.json': json.dumps({'listen': ':18443',
            'tls': {'cert': REMOTE + '/cert.pem', 'key': REMOTE + '/key.pem'},
            'auth': {'type': 'password', 'password': password}, 'disableUDP': True}).encode(),
        'remote.yml': json.dumps({'host': '0.0.0.0', 'port': 18443, 'transport': 'http3',
            'bizThreads': 4, 'workerThreads': 2, 'bossThreads': 1,
            'cipher': 'chacha20-poly1305-v2', 'cipherKey': key, 'maxStreams': 128,
            'http3': {'certificateFile': REMOTE + '/cert.pem', 'privateKeyFile': REMOTE + '/key.pem',
                      'idleTimeoutMs': 10000, 'maxStreamsPerConnection': 128}}).encode()}
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode='w:gz') as tar:
        for name, content in files.items():
            info = tarfile.TarInfo(name)
            info.size = len(content)
            info.mode = 0o700 if name in ['ssserver', 'hysteria'] else 0o600
            tar.addfile(info, io.BytesIO(content))
    ssh('mkdir -m 700 ' + REMOTE + ' && tar -xz -C ' + REMOTE, buf.getvalue())
    ssh('openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -days 2 '
        '-subj /CN=54.172.101.190 -addext subjectAltName=IP:54.172.101.190 '
        '-keyout ' + REMOTE + '/key.pem -out ' + REMOTE + '/cert.pem 2>/dev/null')
    (RUN / 'cert.pem').write_bytes(ssh('cat ' + REMOTE + '/cert.pem'))
    (RUN / 'logback.xml').write_text(logback)
    metadata = json.loads(remote('setup'))
    metadata.update({'shadowsocks': '1.25.0', 'hysteria': release['tag_name'],
                     'ss_archive_sha256': checksum, 'hysteria_sha256': asset['digest'],
                     'client_jar_sha256': hashlib.sha256((ROOT / 'proxy-local/target/proxy-local-1.0.0-SNAPSHOT.jar').read_bytes()).hexdigest(),
                     'git_head': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()})
    (RUN / 'environment.json').write_text(json.dumps(metadata, indent=2))
    print(json.dumps(metadata), flush=True)


if __name__ == '__main__':
    main()
