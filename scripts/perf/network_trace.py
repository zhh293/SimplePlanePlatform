"""Read-only TCP diagnostics for the benchmark endpoint; no traffic injection."""
import json
from pathlib import Path
import subprocess
import time

from prepare_comparison import RUN

deadline=time.monotonic()+1800
with (RUN/'tcp-diagnostics.jsonl').open('a',buffering=1) as output:
    while time.monotonic()<deadline:
        controllers=[]
        for proc in Path('/proc').iterdir():
            if not proc.name.isdigit():continue
            try:
                args=(proc/'cmdline').read_bytes().split(b'\0')
                if b'/mnt/e/SimplePlanePlatform/scripts/perf/compare_proxies.py' in args:
                    controllers.append(proc.name)
            except (FileNotFoundError,PermissionError,ProcessLookupError):pass
        if not controllers:break
        result=subprocess.run(['ss','-tin','dst','54.172.101.190:9090'],capture_output=True,text=True,timeout=3)
        output.write(json.dumps({'time':time.time(),'tcp_info':result.stdout,'error':result.stderr})+'\n')
        time.sleep(2)
