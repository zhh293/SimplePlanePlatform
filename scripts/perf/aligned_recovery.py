"""Supplementary QUIC recovery check with effective idle timeout aligned to 30s."""
import json
import threading
from pathlib import Path

import compare_proxies as bench
from prepare_comparison import RUN, REMOTE, remote, ssh


def main():
    output=RUN/'aligned-recovery-samples.json'
    bench.save=lambda:output.write_text(json.dumps(bench.samples,indent=2))
    original=bench.config
    def config(tool,port):
        cmd=original(tool,port)
        if tool=='simpleplane':
            path=Path(cmd[-1]);value=json.loads(path.read_text())
            value['remoteServers'][0]['http3']['idleTimeoutMs']=30000
            path.write_text(json.dumps(value))
        return cmd
    bench.config=config
    change="""import json
from pathlib import Path
p=Path('/home/ec2-user/simpleplane-perf-20261030/remote.yml')
v=json.loads(p.read_text());v['http3']['idleTimeoutMs']=VALUE;p.write_text(json.dumps(v))
"""
    ssh('python3 -',change.replace('VALUE','30000').encode())
    bench.running.set()
    monitor=threading.Thread(target=bench.monitor,daemon=True);monitor.start()
    try:
        for tool in ['simpleplane','hysteria']:
            try:
                port=bench.start(tool,'aligned_30s')
                bench.phase='aligned_30s_recovery'
                bench.recovery(port,tool,'aligned_30s')
            except Exception as exc:
                bench.samples.append({'tool':tool,'kind':'aligned_failure','ok':False,
                                      'error':repr(exc),'block':'aligned_30s'})
            bench.save();bench.stop();remote('stop',tool);remote('restore')
            bench.emit({'aligned_30s_completed':tool})
    finally:
        bench.stop();bench.running.clear();monitor.join(timeout=2)
        bench.save()
        ssh('python3 -',change.replace('VALUE','10000').encode())
        (RUN/'server-metrics.jsonl').write_bytes(ssh('sudo -n cat '+REMOTE+'/server-metrics.jsonl'))
        cleanup=json.loads(remote('cleanup'))
        (RUN/'cleanup.json').write_text(json.dumps(cleanup,indent=2))
        bench.emit({'cleanup':cleanup})


if __name__=='__main__':
    main()
