#!/usr/bin/env python3
"""Linux/WSL: actual Android TUN stack -> encrypted H2/H3 -> Java -> UDP echo.

Requires built proxy-remote fat jar, cargo, Java 17 and openssl. Only loopback
listeners and disposable test certificates are used. No deployed service changes.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]

def run(command, **kwargs):
    subprocess.run(command, check=True, cwd=ROOT, **kwargs)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--offline', action='store_true')
    parser.add_argument('--transports', nargs='+', default=['http2', 'http3'])
    parser.add_argument('--ciphers', nargs='+', default=['chacha20', 'chacha20-poly1305-v2', 'aes-gcm-v2'])
    args = parser.parse_args()
    jar = ROOT / 'proxy-remote/target/proxy-remote-1.0.0-SNAPSHOT.jar'
    if not jar.exists():
        parser.error('Build proxy-remote first: mvn -pl proxy-remote -am package -DskipTests')
    base = ROOT / '.build/android-udp'
    base.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix='run-', dir=base))
    print('UDP_TEST_ARTIFACTS=' + str(work), flush=True)
    quiet = dict(stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', str(work/'ca.key'), '-out', str(work/'ca.pem'), '-subj', '/CN=UDP test CA', '-days', '2'], **quiet)
    run(['openssl', 'req', '-new', '-newkey', 'rsa:2048', '-nodes', '-keyout', str(work/'server.key'), '-out', str(work/'server.csr'), '-subj', '/CN=localhost'], **quiet)
    (work/'extensions').write_text('subjectAltName=DNS:localhost\nbasicConstraints=CA:FALSE\nkeyUsage=digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n')
    run(['openssl', 'x509', '-req', '-in', str(work/'server.csr'), '-CA', str(work/'ca.pem'), '-CAkey', str(work/'ca.key'), '-CAcreateserial', '-out', str(work/'server.pem'), '-days', '2', '-extfile', str(work/'extensions')], **quiet)
    (work/'logback.xml').write_text('<configuration><appender name="C" class="ch.qos.logback.core.ConsoleAppender"><encoder><pattern>%level %logger - %msg%n</pattern></encoder></appender><root level="WARN"><appender-ref ref="C"/></root></configuration>')
    run(['javac', '-encoding', 'UTF-8', '-cp', str(jar), '-d', str(work), str(ROOT/'scripts/udp/AndroidUdpFixture.java')])
    results = []
    for transport in args.transports:
        for cipher in args.ciphers:
            kind = socket.SOCK_DGRAM if transport == 'http3' else socket.SOCK_STREAM
            with socket.socket(socket.AF_INET, kind) as sock:
                sock.bind(('127.0.0.1', 0)); port = sock.getsockname()[1]
            label = transport + '-' + cipher
            metrics = work / (label + '-sessions.txt')
            with (work/(label+'-java.log')).open('wb') as log:
                server = subprocess.Popen(['java', '-XX:ActiveProcessorCount=2', '-Xmx192m', '-Dlogback.configurationFile='+str(work/'logback.xml'), '-cp', str(work)+os.pathsep+str(jar), 'AndroidUdpFixture', str(port), transport, cipher, str(work), str(metrics)], stdout=log, stderr=subprocess.STDOUT, cwd=ROOT)
                try:
                    deadline = time.monotonic() + 20
                    while not metrics.exists():
                        if server.poll() is not None: raise RuntimeError('Java fixture exited; inspect '+str(work/(label+'-java.log')))
                        if time.monotonic() >= deadline: raise TimeoutError('Java fixture startup')
                        time.sleep(.1)
                    env = os.environ.copy()
                    env.update(UDP_TEST_TRANSPORT=transport, UDP_TEST_CIPHER=cipher, UDP_TEST_PORT=str(port), UDP_TEST_CA=str(work/'ca.pem'))
                    command = ['cargo', 'test', '--locked', '--manifest-path', 'plane-core/Cargo.toml', '--test', 'udp_data_plane']
                    if args.offline: command.append('--offline')
                    command += ['java_remote_udp_actual_tun_end_to_end', '--', '--ignored', '--exact', '--nocapture']
                    start = time.monotonic()
                    with (work/(label+'-rust.log')).open('wb') as rustlog:
                        run(command, env=env, stdout=rustlog, stderr=subprocess.STDOUT, timeout=120)
                    deadline = time.monotonic() + 10
                    while True:
                        try: active = int(metrics.read_text())
                        except ValueError: active = -1
                        if active == 0: break
                        if time.monotonic() >= deadline: raise AssertionError(f'leaked Java outbound sessions: {active}')
                        time.sleep(.1)
                    result = dict(transport=transport, cipher=cipher, passed=True, java_sessions_after_stop=active, seconds=round(time.monotonic()-start, 2))
                    results.append(result)
                    print(json.dumps(result), flush=True)
                finally:
                    server.terminate()
                    try: server.wait(timeout=10)
                    except subprocess.TimeoutExpired: server.kill(); server.wait()
    (work/'results.json').write_text(json.dumps(results, indent=2)+'\n')
    print('ANDROID_UDP_MATRIX_OK', len(results), flush=True)

if __name__ == '__main__':
    main()
