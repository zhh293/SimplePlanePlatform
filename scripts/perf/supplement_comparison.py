"""Equal-size shorter transfers and a bounded process-stall resilience check.

Run only after compare_proxies.py has finished. Preserves the main samples.
"""
import json
import threading
import time

import compare_proxies as bench
from prepare_comparison import RUN, REMOTE, ssh, remote


def main():
    output=RUN/'supplement-samples.json'
    bench.save=lambda: output.write_text(json.dumps(bench.samples,indent=2))
    bench.running.set()
    monitor=threading.Thread(target=bench.monitor,daemon=True)
    monitor.start()
    try:
        for tool in bench.TOOLS:
            try:
                port=bench.start(tool,'supplement')
            except Exception as exc:
                bench.samples.append({'kind':'supplement_setup_failure','tool':tool,'block':'supplement',
                                      'ok':False,'time':time.time(),'error':repr(exc)})
                bench.save();bench.stop();remote('stop',tool);remote('restore')
                continue
            bench.phase='supplement_warmup'
            for _ in range(3):bench.fetch(port)
            for size in [1048576,8388608]:
                bench.phase='supplement_download'
                for index in range(3):
                    time.sleep(1.5)
                    bench.record('download_'+str(size),lambda size=size:bench.fetch(port,size,timeout=90),
                                 tool,'supplement',index=index)
                    bench.save()
            bench.phase='supplement_stall'
            with bench.tunnel(port) as sock:
                bench.fetch(port,sock=sock)
                remote('stall',tool)
                bench.record('existing_stream_after_3s_process_pause',lambda:bench.fetch(port,sock=sock,timeout=15),
                             tool,'supplement')
            time.sleep(2)
            bench.record('new_stream_after_pause',lambda:bench.fetch(port),tool,'supplement')
            bench.save()
            bench.emit({'supplement_completed':tool})
            bench.stop();remote('stop',tool);remote('restore')
    finally:
        bench.stop();bench.running.clear();monitor.join(timeout=2)
        bench.save()
        (RUN/'server-metrics.jsonl').write_bytes(ssh('sudo -n cat '+REMOTE+'/server-metrics.jsonl'))
        cleanup=json.loads(remote('cleanup'))
        (RUN/'cleanup.json').write_text(json.dumps(cleanup,indent=2))
        bench.emit({'cleanup':cleanup})


if __name__=='__main__':
    main()
