"""Same-origin WAN proxy benchmark; standard library only. Run inside local WSL.

Requires prepare_comparison.py. All clients use SOCKS5 domain forwarding; no system
proxy changes. Results include failed samples, resource traces and cleanup proof.
"""
import argparse
import concurrent.futures
import hashlib
import http.client
import json
import math
import os
from pathlib import Path
import socket
import ssl
import statistics
import struct
import subprocess
import threading
import time

from prepare_comparison import ROOT, RUN, REMOTE, SSH, remote, ssh

TOOLS = ['simpleplane', 'shadowsocks', 'hysteria']
ENV = json.loads((RUN / 'environment.json').read_text())
CREDS = json.loads((RUN / 'credentials.private.json').read_text())
HOST = ENV['hostname']
BLOCK = bytes(range(256)) * 256
TLS = ssl.create_default_context(cafile=str(RUN / 'cert.pem'))
TLS.check_hostname = False  # Private origin name differs from IP SAN; CA remains verified.
PUBLIC_TLS = ssl.create_default_context()
samples = []
phase = 'startup'
running = threading.Event()
process = None
current = None


def emit(value):
    print(json.dumps(value, ensure_ascii=False), flush=True)


def save():
    (RUN / 'samples.json').write_text(json.dumps(samples, indent=2))


def read_n(sock, size):
    output = bytearray()
    while len(output) < size:
        data = sock.recv(size - len(output))
        if not data:
            raise EOFError('Truncated socket')
        output.extend(data)
    return bytes(output)


def tunnel(port, host=HOST, target_port=18880, timeout=15):
    sock = socket.create_connection(('127.0.0.1', port), timeout=timeout)
    sock.settimeout(timeout)
    try:
        sock.sendall(b'\x05\x01\x00')
        assert read_n(sock, 2) == b'\x05\x00', 'SOCKS auth failed'
        name = host.encode('idna')
        sock.sendall(b'\x05\x01\x00\x03' + bytes([len(name)]) + name + struct.pack('!H', target_port))
        reply = read_n(sock, 4)
        assert reply[:2] == b'\x05\x00', 'SOCKS connect failed: ' + repr(reply)
        if reply[3] == 1:
            read_n(sock, 4)
        elif reply[3] == 4:
            read_n(sock, 16)
        elif reply[3] == 3:
            read_n(sock, read_n(sock, 1)[0])
        else:
            raise AssertionError('Invalid SOCKS address type')
        read_n(sock, 2)
        return sock
    except BaseException:
        sock.close()
        raise


def fetch(port, size=1024, tls=False, sock=None, upload=False, host=HOST, target_port=None, path=None, timeout=20):
    started = time.perf_counter()
    deadline = started + timeout
    owned = sock is None
    def budget():
        left = deadline-time.perf_counter()
        if left <= 0:
            raise TimeoutError('Overall request time budget exceeded')
        sock.settimeout(left)
    try:
        if sock:
            sock.settimeout(timeout)
        if owned:
            sock = tunnel(port, host, target_port or (18881 if tls else 18880), timeout)
        connected = time.perf_counter()
        budget()
        if tls:
            ctx = TLS if host == HOST else PUBLIC_TLS
            sock = ctx.wrap_socket(sock, server_hostname='54.172.101.190' if host == HOST else host)
        secured = time.perf_counter()
        method = 'POST' if upload else 'GET'
        path = path or ('/upload' if upload else '/bytes/' + str(size))
        headers = f'{method} {path} HTTP/1.1\r\nHost: {host}\r\nConnection: keep-alive\r\nAccept-Encoding: identity\r\n'
        if upload:
            headers += f'Content-Length: {size}\r\n'
        sock.sendall((headers + '\r\n').encode())
        if upload:
            for offset in range(0, size, len(BLOCK)):
                budget()
                sock.sendall(BLOCK[:min(len(BLOCK), size - offset)])
        budget()
        response = http.client.HTTPResponse(sock)
        response.begin()
        first = time.perf_counter()
        digest = hashlib.sha256()
        expected = hashlib.sha256()
        count = 0
        body = bytearray()
        while True:
            budget()
            data = response.read(65536)
            if not data:
                break
            digest.update(data)
            if upload:
                body.extend(data)
            count += len(data)
        if host == HOST:
            if upload:
                for offset in range(0, size, len(BLOCK)):
                    expected.update(BLOCK[:min(len(BLOCK), size-offset)])
                assert json.loads(body) == {'bytes': size, 'sha256': expected.hexdigest()}, 'Upload corruption'
            else:
                assert count == size, f'Wrong body length: {count}/{size}'
                for offset in range(0, size, len(BLOCK)):
                    expected.update(BLOCK[:min(len(BLOCK), size-offset)])
                assert digest.digest() == expected.digest(), 'Download corruption'
            assert response.status == 200, response.status
        else:
            assert 200 <= response.status < 400 and count > 0, response.status
        ended = time.perf_counter()
        return {'ok': True, 'setup_ms': (connected-started)*1000,
                'tls_ms': (secured-connected)*1000, 'ttfb_ms': (first-started)*1000,
                'total_ms': (ended-started)*1000, 'bytes': count,
                'payload_bytes': size if upload else count,
                'mib_s': (size if upload else count)/1048576/(ended-started),
                'status': response.status, 'sha256': digest.hexdigest()}
    finally:
        if owned and sock:
            sock.close()


def record(kind, fn, tool, block, **extra):
    started = time.time()
    try:
        value = fn()
    except Exception as exc:
        value = {'ok': False, 'error': type(exc).__name__ + ': ' + str(exc)}
    value.update({'kind': kind, 'tool': tool, 'block': block, 'time': started, **extra})
    samples.append(value)
    return value


def proc_metric(pid):
    path = Path('/proc', str(pid))
    stat = (path / 'stat').read_text().rsplit(')', 1)[1].split()
    status = (path / 'status').read_text().splitlines()
    result = {s.split(':')[0]: int(s.split()[1]) for s in status
              if s.startswith(('VmRSS:', 'VmHWM:', 'VmSwap:', 'Threads:'))}
    result.update({'cpu_s': (int(stat[11])+int(stat[12]))/os.sysconf('SC_CLK_TCK'),
                   'fds': len(list((path/'fd').iterdir())), 'pid': pid})
    return result


def monitor():
    with (RUN / 'client-metrics.jsonl').open('a', buffering=1) as out:
        while running.is_set():
            if process and process.poll() is None:
                try:
                    out.write(json.dumps({'time': time.time(), 'tool': current, 'phase': phase,
                                          **proc_metric(process.pid)}) + '\n')
                except (FileNotFoundError, ProcessLookupError):
                    pass
            time.sleep(.5)


def config(tool, port):
    if tool == 'shadowsocks':
        value = {'server': '54.172.101.190', 'server_port': 9090,
                 'password': CREDS['password'], 'method': 'chacha20-ietf-poly1305',
                 'local_address': '127.0.0.1', 'local_port': port, 'mode': 'tcp_only',
                 'no_delay': True, 'runtime': {'mode': 'multi_thread', 'worker_count': 2}}
        cmd = [str(RUN/'sslocal'), '-c']
    elif tool == 'hysteria':
        value = {'server': '54.172.101.190:9090', 'auth': CREDS['password'],
                 'tls': {'sni': '54.172.101.190', 'ca': str(RUN/'cert.pem')},
                 'socks5': {'listen': '127.0.0.1:' + str(port)},
                 'quic': {'maxIdleTimeout': '10s'}}
        cmd = [str(RUN/'hysteria'), 'client', '-c']
    else:
        value = {'localPort': port, 'connectionsPerNode': 1, 'timeoutMs': 5000,
                 'systemProxy': {'enabled': False}, 'route': {'defaultRoute': 'proxy'},
                 'nodeSelection': {'mode': 'manual', 'selectedNodeId': 'bench'},
                 'remoteServers': [{'id': 'bench', 'host': '54.172.101.190', 'port': 9090,
                    'transport': 'http3', 'cipher': 'chacha20-poly1305-v2', 'cipherKey': CREDS['key'],
                    'http3': {'serverName': '54.172.101.190', 'caFile': str(RUN/'cert.pem'),
                              'idleTimeoutMs': 10000, 'handshakeTimeoutMs': 5000}}]}
        cmd = ['java', '-XX:ActiveProcessorCount=2', '-Xms32m', '-Xmx96m',
               '-XX:MaxDirectMemorySize=48m', '-Xss256k',
               '-Dlogback.configurationFile=' + str(RUN/'logback.xml'), '-jar',
               str(ROOT/'proxy-local/target/proxy-local-1.0.0-SNAPSHOT.jar')]
    path = RUN / (tool+'.private.json')
    path.write_text(json.dumps(value)); path.chmod(0o600)
    return cmd + [str(path)]


def start(tool, block):
    global process, current, phase
    current = tool
    phase = 'cold_start'
    emit({'starting': tool, 'block': block})
    remote('start', tool)
    time.sleep(1.5)
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        port = listener.getsockname()[1]
    log = (RUN / (tool + '-client.log')).open('ab')
    env = dict(os.environ, GOMAXPROCS='2', GOMEMLIMIT='160MiB')
    before = time.perf_counter()
    process = subprocess.Popen(config(tool, port), stdout=log, stderr=log, cwd=ROOT, env=env)
    deadline = time.monotonic()+30
    errors = []
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(tool + ' exited; inspect client log')
        try:
            value = fetch(port, timeout=3)
            value.update({'kind': 'cold_start', 'tool': tool, 'block': block,
                          'time': time.time(), 'total_ms': (time.perf_counter()-before)*1000})
            samples.append(value)
            save()
            return port
        except Exception as exc:
            errors.append(str(exc))
            time.sleep(.1)
    raise RuntimeError(tool+' not reachable: '+repr(errors[-3:]))


def stop():
    global process
    if process and process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill(); process.wait(timeout=5)
    process = None


def parallel(port, workers, size, count=None):
    count = count or workers
    before = time.perf_counter()
    def work(_):
        try:
            return fetch(port, size, timeout=60 if size>=1048576 else 20)
        except Exception as exc:
            return {'ok': False, 'error': type(exc).__name__ + ': ' + str(exc)}
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
        results = list(pool.map(work, range(count)))
    seconds = time.perf_counter()-before
    total = sum(r.get('payload_bytes', 0) for r in results if r['ok'])
    return {'ok': all(r['ok'] for r in results), 'requests': results,
            'total_ms': seconds*1000, 'mib_s': total/1048576/seconds,
            'rps': sum(r['ok'] for r in results)/seconds, 'attempted_rps': count/seconds,
            'successes': sum(r['ok'] for r in results), 'count': count}


def webpage(port):
    started = time.perf_counter()
    doc = fetch(port, 32768, tls=True)
    # Six persistent TLS sockets fetching twelve assets, similar to an HTTP/1.1 page.
    def assets(_):
        with tunnel(port, target_port=18881) as raw:
            with TLS.wrap_socket(raw, server_hostname='54.172.101.190') as sock:
                return [fetch(port, 32768, sock=sock) for _ in range(2)]
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        results = list(pool.map(assets, range(6)))
    return {'ok': True, 'total_ms': (time.perf_counter()-started)*1000,
            'document_ttfb_ms': doc['ttfb_ms'], 'assets': 12, 'bytes': 13*32768}


def recovery(port, tool, block):
    global phase
    phase = 'recovery'
    old = tunnel(port)
    fetch(port, sock=old)
    try:
        remote('stop', tool)
        stopped_at = time.perf_counter()
        time.sleep(3)
        old_result = record('old_stream_after_outage', lambda: fetch(port, sock=old, timeout=3), tool, block)
        old_result['expected_disconnect'] = not old_result['ok']
        restart_started = time.perf_counter()
        remote('start', tool)
        restored = time.perf_counter()
        attempts = []
        deadline = time.monotonic()+45
        while time.monotonic() < deadline:
            try:
                result = fetch(port, timeout=3)
                attempts.append({'ok': True})
                result.update({'kind': 'recovery', 'tool': tool, 'block': block,
                               'time': time.time(), 'total_ms': (time.perf_counter()-restored)*1000,
                               'restart_to_success_ms': (time.perf_counter()-restart_started)*1000,
                               'attempts': attempts, 'minimum_outage_s': 3,
                               'stop_ack_to_restart_request_s': restart_started-stopped_at})
                samples.append(result)
                return
            except Exception as exc:
                attempts.append({'ok': False, 'error': str(exc)})
                time.sleep(.25)
        samples.append({'kind': 'recovery', 'tool': tool, 'block': block,
                        'time': time.time(), 'ok': False, 'attempts': attempts, 'deadline_s': 45})
    finally:
        old.close()


def run_block(tool, block):
    global phase
    port = start(tool, block)
    phase = 'warmup'
    for _ in range(5):
        fetch(port)
    phase = 'idle'
    time.sleep(3)
    baseline = proc_metric(process.pid)
    cpu = baseline['cpu_s']
    started = time.perf_counter()
    phase = 'latency'
    for i in range(15):
        record('new_connection_http', lambda: fetch(port), tool, block, index=i)
    with tunnel(port) as keepalive:
        for i in range(20):
            record('keepalive_http', lambda: fetch(port, sock=keepalive), tool, block, index=i)
    for i in range(6):
        record('new_connection_https', lambda: fetch(port, tls=True), tool, block, index=i)
    for i in range(3):
        record('synthetic_webpage', lambda: webpage(port), tool, block, index=i)
    phase = 'throughput'
    record('download_32mib', lambda: fetch(port, 32*1048576, timeout=60), tool, block)
    record('upload_8mib', lambda: fetch(port, 8*1048576, upload=True, timeout=60), tool, block)
    time.sleep(1.5)  # Separate fixed-second rate-limit windows; keep failed samples.
    record('parallel_download_4x8mib', lambda: parallel(port, 4, 8*1048576), tool, block)
    time.sleep(1.5)
    phase = 'concurrency'
    for n in [1, 4, 8, 16]:
        record('concurrent_small_requests', lambda n=n: parallel(port, n, 1024, 32),
               tool, block, concurrency=n)
        time.sleep(1.5)
    phase = 'latency_under_load'
    with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
        load = pool.submit(fetch, port, 16*1048576, timeout=60)
        time.sleep(.15)
        for i in range(12):
            record('loaded_http', lambda: fetch(port), tool, block, index=i)
        record('background_download_16mib', load.result, tool, block)
    phase = 'public_https'
    for host in ['example.com', 'www.wikipedia.org', 'www.cloudflare.com']:
        for i in range(2):
            record('public_https', lambda host=host: fetch(port, host=host, tls=True,
                   target_port=443, path='/', timeout=15), tool, block, host=host, index=i)
    phase = 'resource_end'
    end = proc_metric(process.pid)
    samples.append({'kind': 'resource_block', 'tool': tool, 'block': block, 'ok': True,
                    'time': time.time(), 'baseline': baseline, 'end': end,
                    'cpu_s': end['cpu_s']-cpu, 'elapsed_s': time.perf_counter()-started})
    recovery(port, tool, block)
    phase = 'idle_after_load'
    time.sleep(3)
    save()
    failures = [s for s in samples if s['tool']==tool and s['block']==block and not s['ok']
                and not s.get('expected_disconnect')]
    emit({'completed': tool, 'block': block, 'failures': len(failures),
          'rss_mib': end['VmRSS']/1024, 'cpu_s': end['cpu_s']-cpu})
    stop()
    remote('stop', tool)
    remote('restore')


def summarize():
    summary = {}
    for tool in TOOLS:
        summary[tool] = {}
        def label(s):
            return s['kind'] + ('/concurrency='+str(s['concurrency']) if 'concurrency' in s else '') + ('/'+s['host'] if 'host' in s else '')
        for kind in sorted(set(label(s) for s in samples if s['tool']==tool)):
            rows = [s for s in samples if s['tool']==tool and label(s)==kind]
            good = [s for s in rows if s['ok']]
            item = {'n': len(rows), 'successes': len(good), 'failures': len(rows)-len(good)}
            for key in ['total_ms', 'setup_ms', 'tls_ms', 'ttfb_ms', 'mib_s', 'rps', 'cpu_s', 'restart_to_success_ms']:
                values = sorted(s[key] for s in good if key in s)
                if values:
                    item[key] = {'median': statistics.median(values),
                                  'p95': values[math.ceil(.95*len(values))-1],
                                  'min': min(values), 'max': max(values)}
            summary[tool][kind] = item
    (RUN/'summary.json').write_text(json.dumps(summary, indent=2))
    return summary


def main():
    global phase
    parser = argparse.ArgumentParser()
    parser.add_argument('--probe', action='store_true')
    parser.add_argument('--rounds', type=int, default=3)
    parser.add_argument('--tools', default=','.join(TOOLS))
    parser.add_argument('--resume', action='store_true')
    args = parser.parse_args()
    if args.resume:
        samples.extend(json.loads((RUN/'samples.json').read_text()))
    running.set()
    monitor_thread = threading.Thread(target=monitor, daemon=True)
    monitor_thread.start()
    error = None
    try:
        # Latin-square ordering distributes time-of-day drift across implementations.
        for block in range(args.rounds if not args.probe else 1):
            chosen = args.tools.split(',')
            assert all(t in TOOLS for t in chosen)
            order = chosen[block % len(chosen):] + chosen[:block % len(chosen)]
            for tool in order:
                if args.resume and any(r['tool']==tool and r['block']==block and r['kind']=='recovery' for r in samples):
                    continue
                try:
                    if args.probe:
                        port = start(tool, block)
                        emit({'probe': tool, 'result': fetch(port, tls=True)})
                        stop(); remote('stop', tool); remote('restore')
                    else:
                        run_block(tool, block)
                except Exception as exc:
                    samples.append({'tool': tool, 'kind': 'block_error', 'block': block,
                                    'ok': False, 'time': time.time(), 'error': repr(exc)})
                    save()
                    emit({'error': tool, 'block': block, 'reason': repr(exc)})
                    stop()
                    remote('stop', tool)
                    remote('restore')
                    if args.probe:
                        raise
                save()
    except BaseException as exc:
        error = exc
    finally:
        stop(); running.clear(); monitor_thread.join(timeout=2)
        save(); summarize()
        if not args.probe or error:
            (RUN/'server-metrics.jsonl').write_bytes(ssh('sudo -n cat '+REMOTE+'/server-metrics.jsonl'))
            cleanup = json.loads(remote('cleanup'))
            (RUN/'cleanup.json').write_text(json.dumps(cleanup, indent=2))
            emit({'cleanup': cleanup})
        else:
            remote('restore')
    if error:
        raise error
    emit({'complete': True, 'samples': len(samples), 'summary': str(RUN/'summary.json')})


if __name__ == '__main__':
    main()
