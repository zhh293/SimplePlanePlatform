"""Bounded, isolated origin and proxy fixtures. Never changes existing services."""
import hashlib
import http.server
import json
import os
from pathlib import Path
import socket
import ssl
import subprocess
import sys
import threading
import time
import signal

BASE = Path('/home/ec2-user/simpleplane-perf-20261030')
PREFIX = 'sp-perf-20261030-'
NAMES = ['origin', 'simpleplane', 'shadowsocks', 'hysteria']
NGINX = Path('/etc/nginx/nginx.conf')
OLD = '        server 127.0.0.1:19090;'
NEW = '        server 127.0.0.1:18443;'


def route(testing):
    if testing:
        watchdog = BASE/'rollback-unit'
        active = watchdog.read_text().strip() if watchdog.exists() else ''
        if not active or subprocess.run(['systemctl','is-active','--quiet',active+'.timer']).returncode:
            active = PREFIX+'rollback-'+str(int(time.time()))
            subprocess.run(['systemd-run', '--quiet', '--collect', '--unit='+active, '--on-active=45m',
                            '/usr/bin/python3', str(BASE/'remote_fixture.py'), 'restore'], check=True)
            watchdog.write_text(active)
    text = NGINX.read_text()
    source, target = (OLD, NEW) if testing else (NEW, OLD)
    if target in text and source not in text:
        return
    assert text.count(source) == 1 and target not in text, 'Unexpected upstream configuration'
    if testing:
        backup = BASE/'nginx-original.sha256'
        if not backup.exists():
            backup.write_text(hashlib.sha256(text.encode()).hexdigest())
    NGINX.write_text(text.replace(source, target, 1))
    try:
        subprocess.run(['nginx', '-t'], check=True, capture_output=True)
        subprocess.run(['systemctl', 'reload', 'nginx'], check=True, capture_output=True)
    except BaseException:
        NGINX.write_text(text)
        subprocess.run(['systemctl', 'reload', 'nginx'], check=False, capture_output=True)
        raise


def available():
    return int(next(x.split()[1] for x in Path('/proc/meminfo').read_text().splitlines()
                    if x.startswith('MemAvailable:')))


def command(args, **kwargs):
    return subprocess.check_output(args, text=True, **kwargs).strip()


def unit(name):
    assert name in NAMES
    return PREFIX + name


def stop(name):
    subprocess.run(['systemctl', 'stop', unit(name)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def launch(name, args, memory='256M'):
    assert available() > 230 * 1024, 'Insufficient memory for additional test process'
    stop(name)
    subprocess.run(['systemctl', 'reset-failed', unit(name)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    subprocess.run(['systemd-run', '--quiet', '--unit=' + unit(name),
                    '--uid=ec2-user', '--property=MemoryMax=' + memory,
                    '--property=MemoryHigh=' + ('192M' if memory == '256M' else memory),
                    '--property=MemorySwapMax=0', '--property=CPUQuota=100%',
                    '--property=TasksMax=128', '--property=RuntimeMaxSec=3600',
                    '--property=WorkingDirectory=' + str(BASE),
                    '--property=StandardOutput=append:' + str(BASE / (name + '.log')),
                    '--property=StandardError=append:' + str(BASE / (name + '.log')),
                    '--'] + args, check=True)


def metric(name):
    cg = Path('/sys/fs/cgroup/system.slice') / (unit(name) + '.service')
    result = {'name': name}
    try:
        result['memory_bytes'] = int((cg / 'memory.current').read_text())
        result['swap_bytes'] = int((cg / 'memory.swap.current').read_text())
        result['cpu'] = dict((a, int(b)) for a, b in (s.split() for s in (cg / 'cpu.stat').read_text().splitlines()))
        result['events'] = dict((a, int(b)) for a, b in (s.split() for s in (cg / 'memory.events').read_text().splitlines()))
        pids = [int(p) for p in (cg / 'cgroup.procs').read_text().split()]
        result['processes'] = []
        for pid in pids:
            try:
                lines = Path('/proc', str(pid), 'status').read_text().splitlines()
                values = {s.split(':')[0]: int(s.split()[1]) for s in lines
                          if s.startswith(('VmRSS:', 'VmHWM:', 'VmSwap:', 'Threads:'))}
                values['pid'] = pid
                values['fds'] = len(list(Path('/proc', str(pid), 'fd').iterdir()))
                result['processes'].append(values)
            except (FileNotFoundError, ProcessLookupError):
                pass
    except FileNotFoundError:
        result['inactive'] = True
    return result


def monitor():
    deadline = time.monotonic() + 3600
    with (BASE / 'server-metrics.jsonl').open('a', buffering=1) as output:
        while time.monotonic() < deadline:
            mem = available()
            sample = {'time': time.time(), 'available_kib': mem,
                      'load': list(os.getloadavg()), 'units': [metric(n) for n in NAMES]}
            output.write(json.dumps(sample) + '\n')
            if mem < 160 * 1024:
                (BASE / 'GUARD-STOP').write_text(json.dumps(sample))
                for name in NAMES:
                    stop(name)
                route(False)
                return
            time.sleep(1)
    for name in NAMES:
        stop(name)
    route(False)


class Origin(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    block = bytes(range(256)) * 256

    def log_message(self, *args):
        pass

    def do_GET(self):
        try:
            if self.path.startswith('/bytes/'):
                size = min(int(self.path.split('/')[2].split('?')[0]), 64 * 1024 * 1024)
            else:
                size = 1024
            self.send_response(200)
            self.send_header('Content-Length', str(size))
            self.send_header('Cache-Control', 'no-store')
            self.end_headers()
            while size:
                part = self.block[:min(size, len(self.block))]
                self.wfile.write(part)
                size -= len(part)
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError, TimeoutError):
            self.close_connection = True

    def do_POST(self):
        left = int(self.headers.get('Content-Length', '0'))
        if not 0 <= left <= 64 * 1024 * 1024:
            self.send_error(413)
            return
        size = left
        digest = hashlib.sha256()
        while left:
            part = self.rfile.read(min(left, 65536))
            if not part:
                self.close_connection = True
                return
            digest.update(part)
            left -= len(part)
        body = json.dumps({'bytes': size, 'sha256': digest.hexdigest()}).encode()
        self.send_response(200)
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)
        self.wfile.flush()


def origin():
    host = socket.gethostbyname(socket.gethostname())
    servers = []
    for port, tls in [(18880, False), (18881, True)]:
        server = http.server.ThreadingHTTPServer((host, port), Origin)
        server.daemon_threads = True
        server.request_queue_size = 64
        if tls:
            ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            ctx.load_cert_chain(BASE / 'cert.pem', BASE / 'key.pem')
            server.socket = ctx.wrap_socket(server.socket, server_side=True)
        servers.append(server)
        threading.Thread(target=server.serve_forever, daemon=True).start()
    while True:
        time.sleep(1)


def main():
    action = sys.argv[1]
    if action == 'origin':
        origin()
    elif action == 'monitor':
        monitor()
    elif action == 'setup':
        assert not (BASE / 'GUARD-STOP').exists(), 'Previous resource guard triggered'
        subprocess.run(['systemctl', 'stop', PREFIX+'monitor'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        launch('origin', ['/usr/bin/python3', str(BASE / 'remote_fixture.py'), 'origin'], '64M')
        subprocess.run(['systemd-run', '--quiet', '--unit=' + PREFIX + 'monitor',
                        '--property=MemoryMax=48M', '--property=RuntimeMaxSec=3600',
                        '/usr/bin/python3', str(BASE / 'remote_fixture.py'), 'monitor'], check=True)
        print(json.dumps({'hostname': socket.getfqdn(), 'origin_port': 18880, 'tls_port': 18881,
                          'available_kib': available(), 'cpus': os.cpu_count(),
                          'production_pid': command(['systemctl', 'show', 'proxy-remote', '-p', 'MainPID', '--value']),
                          'server_jar_sha256': hashlib.sha256(Path('/opt/simpleplane/releases/b125737/proxy-remote.jar').read_bytes()).hexdigest()}))
    elif action == 'start':
        name = sys.argv[2]
        assert name in NAMES[1:]
        for other in NAMES[1:]:
            stop(other)
        if name == 'shadowsocks':
            args = [str(BASE / 'ssserver'), '-c', str(BASE / 'ss-server.private.json')]
        elif name == 'hysteria':
            args = ['/usr/bin/env', 'GOMAXPROCS=2', 'GOMEMLIMIT=160MiB', str(BASE / 'hysteria'),
                    'server', '-c', str(BASE / 'hy-server.private.json')]
        else:
            args = ['/usr/bin/java', '-XX:ActiveProcessorCount=2', '-Xms32m', '-Xmx96m',
                    '-XX:MaxDirectMemorySize=48m', '-Xss256k',
                    '-Dlogback.configurationFile=' + str(BASE / 'logback.xml'),
                    '-cp', str(BASE) + ':/opt/simpleplane/releases/b125737/proxy-remote.jar',
                    'com.proxy.remote.ProxyRemoteServer']
        launch(name, args)
        route(True)
        print(json.dumps({'started': name, 'available_kib': available()}))
    elif action == 'stop':
        stop(sys.argv[2])
    elif action == 'snapshot':
        print(json.dumps({'time': time.time(), 'available_kib': available(), 'units': [metric(n) for n in NAMES]}))
    elif action == 'restore':
        route(False)
    elif action == 'arm':
        route(True)
    elif action == 'stall':
        name=sys.argv[2]
        assert name in NAMES[1:]
        pid=int(command(['systemctl','show',unit(name),'-p','MainPID','--value']))
        assert pid>1
        birth=Path('/proc',str(pid),'stat').read_text().rsplit(')',1)[1].split()[19]
        code="""import os,signal,sys,time
from pathlib import Path
p=int(sys.argv[1]);time.sleep(3)
try:
 birth=Path('/proc',str(p),'stat').read_text().rsplit(')',1)[1].split()[19]
 if birth==sys.argv[2]:os.kill(p,signal.SIGCONT)
except (FileNotFoundError,ProcessLookupError):pass
"""
        subprocess.Popen(['/usr/bin/python3','-c',code,str(pid),birth],stdin=subprocess.DEVNULL,
                         stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,start_new_session=True)
        os.kill(pid,signal.SIGSTOP)
        print(json.dumps({'paused':name,'pid':pid,'resume_after_s':3}))
    elif action == 'cleanup':
        for name in NAMES:
            stop(name)
        route(False)
        subprocess.run(['systemctl', 'stop', PREFIX + 'monitor'], check=False)
        subprocess.run(['systemctl', 'stop', PREFIX + 'rollback.timer'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        watchdog=BASE/'rollback-unit'
        if watchdog.exists():
            name=watchdog.read_text().strip()
            assert name.startswith(PREFIX+'rollback-') and name[len(PREFIX+'rollback-'):].isdigit()
            subprocess.run(['systemctl','stop',name+'.timer'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            watchdog.unlink()
        print(json.dumps({'time':time.time(),'production_active': command(['systemctl', 'is-active', 'proxy-remote']),
                          'production_pid': command(['systemctl', 'show', 'proxy-remote', '-p', 'MainPID', '--value']),
                          'available_kib': available(), 'guard_triggered': (BASE / 'GUARD-STOP').exists(),
                          'nginx_upstream_restored': OLD in NGINX.read_text() and NEW not in NGINX.read_text(),
                          'nginx_sha256': hashlib.sha256(NGINX.read_bytes()).hexdigest(),
                          'nginx_original_sha256': (BASE/'nginx-original.sha256').read_text() if (BASE/'nginx-original.sha256').exists() else None}))
    elif action == 'prune':
        assert BASE.resolve()==Path('/home/ec2-user/simpleplane-perf-20261030') and not BASE.is_symlink()
        assert OLD in NGINX.read_text() and NEW not in NGINX.read_text()
        for name in NAMES:
            result=subprocess.run(['systemctl','show',unit(name),'-p','MainPID','--value'],capture_output=True,text=True)
            assert result.stdout.strip() in ('','0'), 'Owned test service still has a process'
        allowed={'remote_fixture.py','ssserver','hysteria','logback.xml','ss-server.private.json',
                 'hy-server.private.json','remote.yml','key.pem','cert.pem','server-metrics.jsonl',
                 'simpleplane.log','shadowsocks.log','hysteria.log','origin.log','nginx-original.sha256'}
        children=list(BASE.iterdir())
        assert all(p.name in allowed and p.is_file() and not p.is_symlink() for p in children), 'Unexpected fixture content; preserve for inspection'
        freed=sum(p.stat().st_size for p in children)
        for p in children:p.unlink()
        BASE.rmdir()
        print(json.dumps({'temporary_fixture_removed':True,'removed_bytes':freed}))


if __name__ == '__main__':
    main()
