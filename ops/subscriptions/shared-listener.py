"""Manage the isolated candidate and reversible drop-in for a shared 9090 relay."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

ROOT = Path("/opt/simpleplane-subscription-9090")
NODES = {"zhh-1": ("proxy-remote", "/home/ec2-user/http3-service-config"),
         "zhh-2": ("simpleplane-remote", "/etc/simpleplane")}

def run(*args):
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT).strip()

def write(path, data, mode=0o600):
    tmp = path.with_name(path.name + ".next")
    tmp.write_bytes(data)
    tmp.chmod(mode)
    tmp.replace(path)

def ready(port, pid):
    for _ in range(50):
        if pid > 1 and f"pid={pid}," in run("ss","-Hlnup",f"sport = :{port}"):
            return
        time.sleep(.2)
    raise RuntimeError("Candidate JVM did not own the expected UDP listener")

def pid(service):
    return int(run("systemctl","show",service,"-p","MainPID","--value"))

def stage(unit, config):
    material = json.load(sys.stdin)
    if not re.fullmatch("[a-f0-9]{32}",material["key"]):
        raise ValueError("Invalid subscription frame key")
    private = ROOT/"private"
    private.mkdir(mode=0o700,exist_ok=True)
    write(private/"frame.key",material["key"].encode())
    write(private/"device.crt",material["ca"].encode())
    run("openssl","x509","-in",str(private/"device.crt"),"-noout")
    candidate = ROOT/"test-config"
    candidate.mkdir(mode=0o700,exist_ok=True)
    text = (config/"remote.yml").read_text()
    text, count = re.subn(r"^port:\s*19090\s*$","port: 19091",text,flags=re.M)
    if count != 1 or not re.search(r"^host:\s*127[.]0[.]0[.]1\s*$",text,re.M):
        raise RuntimeError("Existing listener needs explicit configuration reconciliation")
    write(candidate/"remote.yml",text.encode())
    service="simpleplane-9090-acceptance"
    if run("systemctl","show",service,"-p","ActiveState","--value")=="active":
        run("systemctl","stop",service)
    # The test JVM binds loopback, has bounded memory and automatically stops.
    run("systemd-run","--quiet","--collect","--unit="+service,"-p","RuntimeMaxSec=12min",
        "-p","MemoryMax=144M","-p","CPUQuota=60%","-p","Nice=8",
        "/usr/bin/java","-XX:ActiveProcessorCount=1","-Xms12m","-Xmx48m","-Xss384k",
        "-Dsimpleplane.deviceCaFile="+str(private/"device.crt"),
        "-Dsimpleplane.subscriptionFrameKeyFile="+str(private/"frame.key"),"-cp",
        str(candidate)+":"+str(ROOT/"candidate.jar"),"com.proxy.remote.ProxyRemoteServer")
    ready(19091,pid(service))

def rollback(unit, drop):
    if drop.exists():
        drop.unlink()
        run("systemctl","daemon-reload")
        run("systemctl","restart",unit)

def activate(unit, config, drop):
    if drop.exists():
        raise RuntimeError("A managed drop-in is already installed")
    args=Path("/proc",str(pid(unit)),"cmdline").read_bytes().decode().strip("\0").split("\0")
    index=args.index("-cp")+1
    if args[index].split(":",1)[0]!=str(config) or args[index+1]!="com.proxy.remote.ProxyRemoteServer":
        raise RuntimeError("Unexpected existing service command")
    args[index]=str(config)+":"+str((ROOT/"candidate.jar").resolve(strict=True))
    credentials="/run/credentials/"+unit+".service/"
    args[1:1]=["-Dsimpleplane.deviceCaFile="+credentials+"subscription-ca.crt",
               "-Dsimpleplane.subscriptionFrameKeyFile="+credentials+"subscription-frame.key"]
    if any(re.search(r"[\n\r\"\\$]",a) for a in args):
        raise RuntimeError("Unsupported existing systemd argument")
    drop.parent.mkdir(mode=0o755,exist_ok=True)
    text=("[Service]\nLoadCredential=subscription-ca.crt:"+str(ROOT/"private/device.crt")+
          "\nLoadCredential=subscription-frame.key:"+str(ROOT/"private/frame.key")+
          "\nExecStart=\nExecStart="+" ".join('"'+a+'"' for a in args)+"\n")
    if run("systemctl","show","simpleplane-9090-acceptance","-p","ActiveState","--value")=="active":
        run("systemctl","stop","simpleplane-9090-acceptance")
    write(drop,text.encode())
    try:
        run("systemctl","daemon-reload")
        if run("systemctl","show",unit,"-p","LoadState","--value")!="loaded":
            raise RuntimeError("The new service definition did not load")
        run("systemctl","restart",unit)
        ready(19090,pid(unit))
    except Exception:
        rollback(unit,drop)
        raise

if __name__=="__main__":
    os.umask(0o077)
    action, ident=sys.argv[1:]
    unit,folder=NODES[ident]
    drop=Path("/etc/systemd/system",unit+".service.d","91-simpleplane-subscription.conf")
    if action=="stage":stage(unit,Path(folder))
    elif action=="activate":activate(unit,Path(folder),drop)
    elif action=="rollback":rollback(unit,drop)
    else:raise ValueError("Unknown operation")
    print(action+" complete for "+ident)
