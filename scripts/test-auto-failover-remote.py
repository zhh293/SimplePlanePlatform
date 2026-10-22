"""Real two-machine failover test. Inputs contain private node settings and temporary echo targets.

Run under Linux/WSL after packaging proxy-local; see docs/sdd/auto-failover/verification.md.
The test temporarily stops the supplied test services and always starts them again on exit.
"""
import argparse
import hashlib
import http.client
import json
import os
from pathlib import Path
import shutil
import socket
import ssl
import statistics
import subprocess
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--run-dir', type=Path, default=ROOT / '.build/auto-failover')
parser.add_argument('--cycles', type=int, default=20)
parser.add_argument('--skip-benchmark', action='store_true')
args = parser.parse_args()
run = args.run_dir
nodes = json.loads((run / 'nodes.private.json').read_text())
targets = json.loads((run / 'targets.json').read_text())
results = {'cycles': [], 'checks': [], 'performance': {}}
if args.skip_benchmark:
    results['performance'] = json.loads((run / 'result.json').read_text())['performance']
    results['checks'].append('manual_vs_auto_both_nodes')
process = None
config_path = run / 'client.private.json'
status_path = Path(str(config_path) + '.status.json')
config = None
stopped = set()


def ssh(name, command):
    user, key = ('ec2-user', 'ladder.pem') if name == 'a' else ('azureuser', 'zhh_key.pem')
    with tempfile.TemporaryDirectory(prefix='sp-auto-key-') as directory:
        private = Path(directory) / 'key'
        shutil.copyfile(ROOT / key, private)
        private.chmod(0o600)
        completed = subprocess.run(['ssh', '-i', str(private), '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=12',
                                    '-o', 'StrictHostKeyChecking=yes', '-o',
                                    'UserKnownHostsFile=' + str(ROOT / '.build/remote-node-test/known_hosts'),
                                    user + '@' + nodes[name]['host'], command], capture_output=True, timeout=45)
        if completed.returncode:
            raise RuntimeError(name + ': ' + completed.stderr.decode(errors='replace'))
        return completed.stdout.decode()


def service(name, action):
    unit = 'proxy-remote' if name == 'a' else 'simpleplane-remote'
    if action == 'stop':
        stopped.add(name)
    ssh(name, 'sudo systemctl ' + action + ' ' + unit)
    if action == 'start':
        stopped.discard(name)


def save():
    data = json.dumps(config).encode()
    temporary = run / 'client.next'
    temporary.write_bytes(data)
    temporary.chmod(0o600)
    temporary.replace(config_path)
    return hashlib.sha256(data).hexdigest()


def status():
    if process.poll() is not None:
        raise AssertionError('Java exited; inspect client.log')
    try:
        return json.loads(status_path.read_text())
    except (FileNotFoundError, json.JSONDecodeError):
        return {}


def wait(predicate, timeout=90, keepalive=None):
    deadline = time.monotonic() + timeout
    next_echo = 0
    while time.monotonic() < deadline:
        value = status()
        if predicate(value):
            return value
        if keepalive and time.monotonic() >= next_echo:
            echo(keepalive, b'healthy-current')
            next_echo = time.monotonic() + 1
        time.sleep(.1)
    raise AssertionError('Status timeout: ' + json.dumps(status()))


def receive(browser, count):
    data = bytearray()
    while len(data) < count:
        block = browser.recv(count - len(data))
        if not block:
            raise AssertionError('Truncated response')
        data.extend(block)
    return bytes(data)


def tunnel(host, target_port, socks=False):
    browser = socket.create_connection(('127.0.0.1', config['localPort']), timeout=15)
    browser.settimeout(20)
    try:
        if socks:
            browser.sendall(bytes([5, 1, 0]))
            assert receive(browser, 2) == bytes([5, 0])
            domain = host.encode()
            browser.sendall(bytes([5, 1, 0, 3, len(domain)]) + domain + target_port.to_bytes(2, 'big'))
            assert receive(browser, 10)[:2] == bytes([5, 0])
        else:
            browser.sendall(f'CONNECT {host}:{target_port} HTTP/1.1\r\nHost: {host}\r\n\r\n'.encode())
            header = bytearray()
            while not header.endswith(b'\r\n\r\n'):
                header.extend(receive(browser, 1))
                assert len(header) < 8192
            assert header.startswith(b'HTTP/1.1 200'), header.decode(errors='replace')
        return browser
    except BaseException:
        browser.close()
        raise


def target_tunnel(name, socks=False):
    return tunnel(targets[name]['host'], targets[name]['port'], socks)


def echo(browser, data):
    browser.sendall(data)
    assert receive(browser, len(data)) == data


def identity(name):
    host, port = targets[name]['host'], targets[name]['port']
    with socket.create_connection(('127.0.0.1', config['localPort']), timeout=20) as browser:
        browser.sendall(f'GET http://{host}:{port}/ HTTP/1.1\r\nHost: {host}:{port}\r\nConnection: close\r\n\r\n'.encode())
        response = http.client.HTTPResponse(browser)
        response.begin()
        assert response.status == 200
        assert response.read() == ('auto-node-' + name).encode()


def https():
    with tunnel('example.com', 443) as browser:
        with ssl.create_default_context().wrap_socket(browser, server_hostname='example.com') as secured:
            secured.sendall(b'GET / HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n')
            response = http.client.HTTPResponse(secured)
            response.begin()
            assert response.status in (200, 301, 302)
            assert response.read()


def record(name):
    results['checks'].append(name)
    persist()
    print('PASS', name, flush=True)


def persist():
    (run / 'result.json').write_text(json.dumps(results, indent=2))


def start(mode, selected):
    global config, process
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        port = listener.getsockname()[1]
    config = {'localPort': port, 'connectionsPerNode': 1, 'timeoutMs': 8000,
              'systemProxy': {'enabled': False}, 'route': {'defaultRoute': 'proxy'},
              'nodeSelection': {'mode': mode, 'selectedNodeId': selected, 'preferredNodeId': selected},
              'remoteServers': list(nodes.values())}
    status_path.unlink(missing_ok=True)
    save()
    with (run / 'client.log').open('ab') as output:
        process = subprocess.Popen(['java', '-XX:ActiveProcessorCount=2', '-Xmx256m', '-jar',
                                    str(ROOT / 'proxy-local/target/proxy-local-1.0.0-SNAPSHOT.jar'), str(config_path)],
                                   cwd=ROOT, stdout=output, stderr=subprocess.STDOUT)
    wait(lambda s: s.get('currentNodeId') == selected and s.get('nodeStatus') == 'available')


def stop():
    if process and process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=20)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)


def benchmark(name, mode):
    start(mode, name)
    samples = []
    with target_tunnel(name) as browser:
        echo(browser, b'warmup')
        for _ in range(8):
            before = time.perf_counter()
            echo(browser, b'x' * 65536)
            samples.append((time.perf_counter() - before) * 1000)
    results['performance'][mode + '-' + name] = {'samples_ms': samples, 'median_ms': statistics.median(samples)}
    stop()


try:
    results['jarSha256'] = hashlib.sha256((ROOT / 'proxy-local/target/proxy-local-1.0.0-SNAPSHOT.jar').read_bytes()).hexdigest()
    if not args.skip_benchmark:
        for name in ['a', 'b']:
            benchmark(name, 'manual')
            benchmark(name, 'auto')
        record('manual_vs_auto_both_nodes')
    start('auto', 'a')
    wait(lambda s: len(s.get('nodes', [])) == 2 and all(n['available'] for n in s['nodes']))
    identity('a')
    https()
    record('preferred_a_identity_and_verified_https')
    for _ in range(3):
        try:
            with tunnel(targets['a']['host'], 1):
                raise AssertionError('Expected target connection refusal')
        except AssertionError as error:
            assert '502' in str(error), str(error)
    assert status()['currentNodeId'] == 'a'
    record('target_refusal_does_not_trip_node')
    for cycle in range(args.cycles):
        previous = status()['currentNodeId']
        following = 'b' if previous == 'a' else 'a'
        with target_tunnel(previous) as old:
            echo(old, b'before-fault')
            began = time.monotonic()
            service(previous, 'stop')
            try:
                switched = wait(lambda s: s.get('currentNodeId') == following and s.get('nodeStatus') == 'available')
                switched_at = time.monotonic()
                switch_seconds = time.monotonic() - began
                assert old.recv(1) == b''
                identity(following)
            finally:
                service(previous, 'start')
        with target_tunnel(following, socks=True) as active:
            echo(active, b'after-fault-socks')
            wait(lambda s: any(n['nodeId'] == previous and n['circuit'] == 'CLOSED' and n['available']
                               for n in s.get('nodes', [])), keepalive=active)
            assert status()['currentNodeId'] == following
            echo(active, b'recovery-did-not-preempt')
            # Exercise stable service before the next fault; otherwise increasing cooldown is expected.
            while time.monotonic() - switched_at < 61:
                echo(active, b'stable-before-next-fault')
                time.sleep(1)
        settled = wait(lambda s: s.get('activeStreams') == 0)
        rss = next(line for line in Path('/proc', str(process.pid), 'status').read_text().splitlines() if line.startswith('VmRSS:'))
        results['cycles'].append({'cycle': cycle + 1, 'from': previous, 'to': following,
                                  'switchSeconds': switch_seconds, 'activeStreamsAfter': settled['activeStreams'], 'rss': rss})
        persist()
        print('PASS_CYCLE', cycle + 1, previous, following, round(switch_seconds, 3), flush=True)
    https()
    record('verified_https_after_repeated_failover')
    for name in ['a', 'b']:
        service(name, 'stop')
    try:
        wait(lambda s: not s.get('currentNodeId') and s.get('nodeStatus') == 'unavailable')
        before = time.monotonic()
        try:
            with target_tunnel('a'):
                raise AssertionError('Expected unavailable cluster')
        except AssertionError as error:
            assert '502' in str(error)
        assert time.monotonic() - before < 2
        record('all_nodes_unavailable_fails_fast')
    finally:
        for name in ['a', 'b']:
            service(name, 'start')
    config['nodeSelection'] = {'mode': 'manual', 'selectedNodeId': 'a'}
    revision = save()
    wait(lambda s: s.get('mode') == 'manual' and s.get('appliedRevision') == revision and s.get('nodeStatus') == 'available')
    identity('a')
    assert len(status()['nodes']) == 1
    record('auto_to_manual_ignores_old_cooldown_and_keeps_one_node')
    config['nodeSelection']['selectedNodeId'] = 'b'
    revision = save()
    wait(lambda s: s.get('currentNodeId') == 'b' and s.get('appliedRevision') == revision and s.get('nodeStatus') == 'available')
    identity('b')
    record('manual_switch_b')
    results['passed'] = True
finally:
    stop()
    for name in list(stopped):
        service(name, 'start')
    config_path.unlink(missing_ok=True)
    persist()
