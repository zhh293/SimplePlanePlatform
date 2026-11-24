"""Refresh zhh-1's HTTP/3 leaf while retaining the trust key used by existing clients."""
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import time

ROOT = Path("/opt/simpleplane-subscription-9090")
DIR = ROOT / "tls"
UNIT = "proxy-remote"
OLD_CONFIG = Path("/home/ec2-user/http3-service-config/remote.yml")
CA_KEY = Path("/run/credentials/proxy-remote.service/quic-key.pem")
DROP = Path("/etc/systemd/system/proxy-remote.service.d/92-simpleplane-server-tls.conf")

def run(*cmd):
    return subprocess.check_output(cmd,stderr=subprocess.PIPE)

def openssl(*args):
    return run("openssl",*map(str,args))

def current(cert):
    return cert.exists() and subprocess.run(["openssl","x509","-in",cert,"-noout","-checkend","2592000"],
        stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0

def prepare():
    DIR.mkdir(mode=0o755,exist_ok=True)
    ca,leaf,key=DIR/"ca.crt",DIR/"server.crt",DIR/"server.key"
    if not current(ca):
        old=Path("/etc/roto-kb/tls/fullchain.pem")
        if openssl("x509","-in",old,"-pubkey","-noout")!=openssl("pkey","-in",CA_KEY,"-pubout"):
            raise RuntimeError("Original CA and server key do not match")
        openssl("req","-x509","-new","-sha256","-key",CA_KEY,"-subj","/CN=54.172.101.190",
                "-days","3650","-addext","basicConstraints=critical,CA:TRUE,pathlen:0",
                "-addext","keyUsage=critical,keyCertSign,cRLSign","-out",DIR/"ca.next")
        (DIR/"ca.next").replace(ca)
        ca.chmod(0o644)
    if current(leaf):
        openssl("verify","-purpose","sslserver","-verify_ip","54.172.101.190","-CAfile",ca,leaf)
        return False
    if not key.exists():
        openssl("genpkey","-algorithm","EC","-pkeyopt","ec_paramgen_curve:prime256v1","-out",key)
    openssl("req","-new","-key",key,"-subj","/CN=zhh-1 relay","-out",DIR/"server.csr")
    (DIR/"extensions").write_text("basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature\n"
                                 "extendedKeyUsage=serverAuth\nsubjectAltName=IP:54.172.101.190\n")
    openssl("x509","-req","-in",DIR/"server.csr","-CA",ca,"-CAkey",CA_KEY,"-set_serial",
            str(secrets.randbits(150)+1),"-days","365","-sha256","-extfile",DIR/"extensions","-out",DIR/"server.next")
    openssl("verify","-purpose","sslserver","-verify_ip","54.172.101.190","-CAfile",ca,DIR/"server.next")
    (DIR/"server.next").chmod(0o644)
    (DIR/"server.next").replace(leaf)
    return True

def config(candidate):
    text=OLD_CONFIG.read_text()
    for setting,value in (("certificateFile",str(DIR/"server.crt")),("privateKeyFile",
            str(DIR/"server.key") if candidate else "/run/credentials/proxy-remote.service/subscription-server.key")):
        text,count=re.subn(r"(?m)^(\s*"+setting+r":)[^\n]*$",lambda match:match[1]+" "+value,text)
        if count!=1:raise RuntimeError("Unexpected TLS configuration")
    if candidate:
        text,count=re.subn(r"(?m)^port:\s*19090\s*$","port: 19091",text)
        if count!=1:raise RuntimeError("Unexpected loopback port")
    dest=(ROOT/"test-config"/"remote.yml") if candidate else (DIR/"remote.yml")
    tmp=dest.with_suffix(".next");tmp.write_text(text)
    if not candidate:
        st=OLD_CONFIG.stat();os.chown(tmp,st.st_uid,st.st_gid);tmp.chmod(st.st_mode&0o777)
    tmp.replace(dest)

def restart():
    run("systemctl","restart",UNIT)
    for _ in range(50):
        pid=run("systemctl","show",UNIT,"-p","MainPID","--value").strip()
        if pid!=b"0" and b"pid="+pid+b"," in run("ss","-Hlnup","sport = :19090"):return
        time.sleep(.2)
    raise RuntimeError("HTTP/3 listener did not become ready")

def rollback():
    DROP.unlink(missing_ok=True);run("systemctl","daemon-reload");restart()

if __name__=="__main__":
    os.umask(0o077);action=sys.argv[1]
    if action=="candidate":
        prepare();config(True)
        unit="simpleplane-9090-acceptance"
        if run("systemctl","show",unit,"-p","ActiveState","--value").strip()==b"active":run("systemctl","stop",unit)
        run("systemd-run","--collect","--unit="+unit,"-p","RuntimeMaxSec=12min","-p","MemoryMax=144M","-p","CPUQuota=60%",
            "/usr/bin/java","-XX:ActiveProcessorCount=1","-Xms12m","-Xmx48m",
            "-Dsimpleplane.deviceCaFile="+str(ROOT/"private/device.crt"),
            "-Dsimpleplane.subscriptionFrameKeyFile="+str(ROOT/"private/frame.key"),"-cp",
            str(ROOT/"test-config")+":"+str(ROOT/"candidate.jar"),"com.proxy.remote.ProxyRemoteServer")
    elif action=="activate":
        config(False)
        # The service user reads the public leaf by path; private files keep their own restricted modes.
        DIR.chmod(0o711)
        service_user=run("systemctl","show",UNIT,"-p","User","--value").decode().strip()
        run("runuser","-u",service_user,"--","test","-r",str(DIR/"server.crt"))
        if subprocess.run(["runuser","-u",service_user,"--","test","-r",str(DIR/"server.key")]).returncode==0:
            raise RuntimeError("The service must load the private key through systemd credentials")
        if DROP.exists():raise RuntimeError("TLS override already exists")
        DROP.write_text("[Service]\nLoadCredential=subscription-server.key:"+str(DIR/"server.key")+
                        "\nBindReadOnlyPaths="+str(DIR/"remote.yml")+":"+str(OLD_CONFIG)+"\n")
        try:
            run("systemctl","daemon-reload");restart()
        except Exception:
            rollback();raise
    elif action=="rollback":rollback()
    elif action=="renew":
        if DROP.exists() and prepare():restart()
    else:raise ValueError("Unknown operation")
    print(action+" completed")
