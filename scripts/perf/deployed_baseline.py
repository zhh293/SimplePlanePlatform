"""Measure the already-deployed service while isolated-port access is pending."""
import json
from pathlib import Path
import socket
import subprocess
import time
import sys
from collections import Counter
import threading

import compare_proxies as bench
from prepare_comparison import ROOT, RUN, remote


def main():
    # Reuse previously verified local connection settings; never fetch server secrets.
    node = json.loads((ROOT/'.build/auto-failover/nodes.private.json').read_text())['a']
    node['id'] = 'bench'
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0)); port = listener.getsockname()[1]
    bench.config('simpleplane', port)
    config_path = RUN/'simpleplane.private.json'
    config = json.loads(config_path.read_text())
    config['remoteServers'] = [node]
    path = RUN/'deployed.private.json'
    path.write_text(json.dumps(config)); path.chmod(0o600)
    cmd = bench.config('simpleplane', port)
    cmd[-1] = str(path)
    log = (RUN/'deployed-client.log').open('ab')
    process = subprocess.Popen(cmd, stdout=log, stderr=log, cwd=ROOT)
    bench.process = process
    bench.current = 'simpleplane-deployed-diagnostic' if '--diagnose' in sys.argv else 'simpleplane-deployed'
    bench.running.set()
    monitor = threading.Thread(target=bench.monitor, daemon=True)
    monitor.start()
    diagnostic = '--diagnose' in sys.argv
    output = RUN/('deployed-diagnostic.json' if diagnostic else 'deployed-baseline.json')
    rows = json.loads(output.read_text()) if '--resume' in sys.argv else []
    previous = list(rows)
    visited = Counter()
    def record(kind, fn):
        bench.phase = kind
        index = visited[kind]
        visited[kind] += 1
        prior = [r for r in previous if r['kind'] == kind]
        if index < len(prior):
            return prior[index]
        started = time.monotonic()
        try:
            value = fn()
        except Exception as exc:
            value = {'ok': False, 'error': repr(exc)}
        value.update({'kind': kind, 'elapsed_s': time.monotonic()-started})
        rows.append(value)
        output.write_text(json.dumps(rows, indent=2))
        print(json.dumps({'kind': kind, **{k:v for k,v in value.items() if k not in ['requests','sha256']}}), flush=True)
        return value
    try:
        print(remote('setup').decode(), flush=True)
        deadline = time.monotonic()+30
        while time.monotonic()<deadline:
            try:
                bench.fetch(port, timeout=3)
                break
            except Exception:
                time.sleep(.2)
        else:
            raise RuntimeError('Existing deployed node not reachable; inspect deployed-client.log')
        for _ in range(5):
            bench.fetch(port)
        if diagnostic:
            for size in [32768, 262144, 1048576, 8388608]:
                time.sleep(1.5)
                record('upload_'+str(size), lambda size=size: bench.fetch(port,size,upload=True,timeout=30))
                time.sleep(1.5)
                record('after_upload_'+str(size), lambda: bench.fetch(port))
            time.sleep(1.5)
            record('parallel_4x8mib', lambda: bench.parallel(port,4,8*1048576))
            for n in [1,4,8,16]:
                time.sleep(1.5)
                record('concurrency_'+str(n), lambda n=n: bench.parallel(port,n,1024,32))
            record('client_resources', lambda: {'ok': True, **bench.proc_metric(process.pid)})
            return
        for _ in range(15):
            record('http_new', lambda: bench.fetch(port))
        with bench.tunnel(port) as sock:
            for _ in range(20):
                record('http_keepalive', lambda: bench.fetch(port, sock=sock))
        for _ in range(6):
            record('https_new', lambda: bench.fetch(port, tls=True))
        for _ in range(3):
            record('synthetic_webpage', lambda: bench.webpage(port))
        record('download_32mib', lambda: bench.fetch(port, 32*1048576, timeout=60))
        record('upload_8mib', lambda: bench.fetch(port, 8*1048576, upload=True, timeout=60))
        record('parallel_4x8mib', lambda: bench.parallel(port, 4, 8*1048576))
        for n in [1,4,8,16]:
            record('concurrency_'+str(n), lambda n=n: bench.parallel(port,n,1024,32))
        for host in ['example.com','www.wikipedia.org','www.cloudflare.com']:
            record('public_'+host, lambda host=host: bench.fetch(port, tls=True, host=host,
                   target_port=443, path='/', timeout=15))
        record('client_resources', lambda: {'ok': True, **bench.proc_metric(process.pid)})
    finally:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill(); process.wait(timeout=5)
        log.close()
        bench.running.clear(); monitor.join(timeout=2)
        cleanup=remote('cleanup')
        (RUN/'cleanup.json').write_bytes(cleanup)
        print(cleanup.decode(), flush=True)


if __name__ == '__main__':
    main()
