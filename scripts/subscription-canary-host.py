"""Run only from a freshly created /var/tmp/sp-sub-<12 hex> on the canary host."""
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import time


directory = Path(__file__).resolve().parent
if directory.parent != Path("/var/tmp") or not re.fullmatch(r"sp-sub-[0-9a-f]{12}", directory.name):
    sys.exit("Unexpected canary directory")
os.chdir(directory)
os.umask(0o077)
run_id = directory.name
db_id = "spg" + run_id[-12:]
public_ip = "212.64.14.155"
image = "eclipse-temurin:17-jre-jammy"


def run(*args, input=None):
    result = subprocess.run(args, input=input, text=True, capture_output=True, timeout=100)
    if result.returncode:
        raise RuntimeError(f"{args[0]} failed ({result.returncode}): {result.stderr[-400:]}")
    return result.stdout.strip()


def mysql(sql):
    return run("docker", "exec", "-i", "mysql-tmd", "sh", "-c",
               'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --batch --skip-column-names --user=root', input=sql)


def resource(suffix): return run_id + "-" + suffix


def volume(name, target):
    return ["-v", f"{directory / name}:{target}:ro"]


def container(name, memory, args):
    return run("docker", "run", "-d", "--name", resource(name), "--label", "simpleplane.canary=" + run_id,
               "--memory", memory, "--memory-swap", memory, "--cpus", "1", "--pids-limit", "150",
               "--security-opt", "no-new-privileges", *args)


def cleanup():
    subprocess.run(["systemctl", "stop", resource("echo.service")], capture_output=True)
    pidfile = directory / "nginx.pid"
    if pidfile.exists():
        pid = int(pidfile.read_text().strip())
        args = Path(f"/proc/{pid}/cmdline")
        if args.exists() and str(directory) in args.read_bytes().decode(errors="replace"):
            os.kill(pid, 15)
    for name in ["client","proxy","portal","sso","pod"]:
        target = resource(name)
        label = subprocess.run(["docker","inspect","--format",'{{index .Config.Labels "simpleplane.canary"}}',target],
                               text=True,capture_output=True)
        if label.returncode == 0 and label.stdout.strip() == run_id:
            run("docker","rm","-f",target)
    if (directory/"db-owned").exists():
        mysql(f"DROP DATABASE IF EXISTS {db_id}_rp; DROP DATABASE IF EXISTS {db_id}_sso;"
              f"DROP USER IF EXISTS '{db_id}_rp'@'%','{db_id}_sso'@'%';")
    print("Owned listeners, services and database fixtures cleaned")


def ready(url, timeout):
    from urllib.parse import urlsplit
    pod = resource("pod")
    deadline = time.monotonic()+timeout
    pid = run("docker","inspect","--format","{{.State.Pid}}",pod)
    while time.monotonic()<deadline:
        result = subprocess.run(["nsenter","-t",pid,"-n","curl","--noproxy","*","-sS","-o","/dev/null",
                                 "-w","%{http_code}","--max-time","2",url],capture_output=True,text=True)
        if result.returncode == 0 and result.stdout in {"200","302","401","403","405"}: return
        time.sleep(1)
    raise RuntimeError("Service did not become ready: " + urlsplit(url).path)


def start_echo():
    pid = run("docker", "inspect", "--format", "{{.State.Pid}}", resource("pod"))
    run("nsenter", "-t", pid, "-n", "ip", "address", "replace", "192.0.2.73/32", "dev", "lo")
    run("systemd-run", "--quiet", "--collect", "--unit=" + resource("echo"),
        "--property=RuntimeMaxSec=2h", "--property=MemoryMax=32M",
        "nsenter", "-t", pid, "-n", "python3", str(Path(__file__).resolve()), "echo")


def echo():
    import asyncio

    async def serve(reader, writer):
        try:
            payload = await asyncio.wait_for(reader.readexactly(32), 10)
            writer.write(b"sp-gray:" + payload)
            await writer.drain()
        finally:
            writer.close()

    class EchoDatagram(asyncio.DatagramProtocol):
        def connection_made(self, transport):
            self.transport = transport

        def datagram_received(self, data, peer):
            if len(data) == 32:
                self.transport.sendto(b"sp-gray:" + data, peer)

    async def main():
        server = await asyncio.start_server(serve, "192.0.2.73", 28371)
        await asyncio.get_running_loop().create_datagram_endpoint(EchoDatagram, local_addr=("192.0.2.73", 28371))
        async with server:
            await server.serve_forever()

    asyncio.run(main())


def setup():
    if Path("db-owned").exists(): raise RuntimeError("Canary directory already initialized")
    if int(re.search(r"MemAvailable:\s+(\d+)",Path("/proc/meminfo").read_text()).group(1)) < 950*1024:
        raise RuntimeError("Insufficient memory headroom for simultaneous services")
    if run("ss","-Hlnut","sport = :443"): raise RuntimeError("443 already in use")
    peer = str(ipaddress.IPv4Address(os.environ["SSH_CONNECTION"].split()[0]))
    config=json.loads(Path("fixture.json").read_text())
    # All schema/user identifiers derive from the random, validated run directory.
    exists=mysql(f"SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME IN ('{db_id}_rp','{db_id}_sso');"
                 f"SELECT User FROM mysql.user WHERE User IN ('{db_id}_rp','{db_id}_sso');")
    if exists: raise RuntimeError("Canary SQL names already exist")
    run("systemd-run","--quiet","--unit="+run_id+"-expiry","--on-active=2h","/usr/bin/python3",str(Path(__file__).resolve()),"expire")
    Path("db-owned").touch(exist_ok=False)
    for role in ["sso","rp"]:
        db = f"{db_id}_{role}"
        password = config[role+"_db_password"]
        assert re.fullmatch("[A-Za-z0-9_-]{32,128}",password)
        mysql(f"CREATE DATABASE {db} CHARACTER SET utf8mb4; CREATE USER '{db}'@'%' IDENTIFIED BY '{password}';"
              f"GRANT ALL ON {db}.* TO '{db}'@'%';")
    pod=resource("pod")
    # The public address is assigned only inside this isolated network namespace.
    container("pod","48m",["--network","middleware-network","-p","443:443/tcp","-p","443:443/udp",
        "redis:7-alpine","redis-server","--bind","127.0.0.1","--save","","--appendonly","no","--maxmemory","24mb"])
    pid=run("docker","inspect","--format","{{.State.Pid}}",pod)
    run("nsenter","-t",pid,"-n","ip","addr","add",public_ip+"/32","dev","lo")
    args=["--network","container:"+pod,"--cap-drop","ALL","--read-only","--tmpfs","/tmp:rw,exec,nosuid,size=48m"]
    java=["-XX:ActiveProcessorCount=2","-Xms24m","-Xmx128m","-XX:ReservedCodeCacheSize=32m","-Xss512k"]
    for role in ["sso","rp"]:
        db=f"{db_id}_{role}"
        prefix="SSO_DB_" if role=="sso" else "SP_DB_"
        keys={prefix+"URL":f"jdbc:mysql://mysql-tmd:3306/{db}?sslMode=PREFERRED&allowPublicKeyRetrieval=true&connectionTimeZone=UTC",
              prefix+("USERNAME" if role=="sso" else "USER"):db,prefix+"PASSWORD":config[role+"_db_password"]}
        keys.update(config[role+"_env"])
        if role == "rp":
            keys["SP_IDENTITY_SCHEMA"] = db_id+"_sso"
        Path(role+".env").write_text("".join(f"{k}={v}\n" for k,v in keys.items()))
    container("sso","336m",args+volume("oidc.p12","/fixtures/oidc.p12")+["--env-file","sso.env",
        "--entrypoint","java","authsystem-sso-server:0.1.2-browser-fix",*java,"-jar","/app/sso-server.jar"])
    ready("http://127.0.0.1:18080/.well-known/openid-configuration",100)
    mysql("USE "+db_id+"_sso;"+Path("seed.sql").read_text())
    identity_sql = Path("console-identity.sql").read_text()
    mysql(identity_sql.replace("sso_authsystem_v2",db_id+"_sso").replace("simpleplane_subscriptions",db_id+"_rp"))
    # Java trust entry for the test HTTPS issuer. The private CA signing key stays on the test machine.
    run("docker","run","--rm","--network","none","--memory","100m","-v",f"{directory}:/work",
        "--entrypoint","keytool",image,"-importcert","-noprompt","-alias","canary","-file","/work/root.crt",
        "-keystore","/work/trust.p12","-storetype","PKCS12","-storepass","changeit")
    start_portal()


def start_portal():
    pod=resource("pod")
    pid=run("docker","inspect","--format","{{.State.Pid}}",pod)
    peer=str(ipaddress.IPv4Address(os.environ["SSH_CONNECTION"].split()[0]))
    args=["--network","container:"+pod,"--cap-drop","ALL","--read-only","--tmpfs","/tmp:rw,exec,nosuid,size=48m"]
    java=["-XX:ActiveProcessorCount=2","-Xms24m","-Xmx128m","-XX:ReservedCodeCacheSize=32m","-Xss512k"]
    nginx = f"""worker_processes 1;
pid {directory}/nginx.pid;
error_log {directory}/nginx-error.log warn;
events {{ worker_connections 256; }}
http {{
  lua_package_path '/www/server/nginx/lib/lua/?.lua;/www/server/nginx/lib/lua/?/init.lua;;';
  lua_package_cpath '/www/server/nginx/lib/lua/?.so;;';
  access_log off;
  client_max_body_size 64k;
  limit_req_zone $binary_remote_addr zone=enroll:128k rate=3r/s;
  server {{
    listen 443 ssl;
    server_name {public_ip};
    ssl_certificate {directory}/https.crt;
    ssl_certificate_key {directory}/https.key;
    ssl_protocols TLSv1.2 TLSv1.3;
    allow {peer}; allow 127.0.0.1; allow {public_ip}; deny all;
    proxy_set_header Host {public_ip};
    proxy_set_header X-Forwarded-Proto https;
    location ~ ^/(account/|admin/|sso/|device/|subscription[.]css$) {{
      limit_req zone=enroll burst=24 nodelay;
      proxy_pass http://127.0.0.1:18181;
    }}
    location / {{ proxy_pass http://127.0.0.1:18080; }}
  }}
}}"""
    Path("nginx.conf").write_text(nginx)
    nginxbin="/www/server/nginx/sbin/nginx"
    run("nsenter","-t",pid,"-n",nginxbin,"-p",str(directory),"-c",str(directory/"nginx.conf"))
    container("portal","300m",args+volume("portal.jar","/app/portal.jar")+volume("device.p12","/fixtures/device.p12")
        +volume("root.crt","/fixtures/root.crt")+volume("trust.p12","/fixtures/trust.p12")+["--env-file","rp.env",
        "--entrypoint","java",image,*java,"-Djavax.net.ssl.trustStore=/fixtures/trust.p12",
        "-Djavax.net.ssl.trustStorePassword=changeit","-jar","/app/portal.jar"])
    ready("http://127.0.0.1:18181/account/devices",90)
    container("proxy","180m",args+volume("proxy.jar","/app/proxy.jar")+volume("remote.yml","/fixtures/remote.yml")
        +volume("https.crt","/fixtures/https.crt")+volume("https.key","/fixtures/https.key")
        +volume("device.crt","/fixtures/device.crt")+["--entrypoint","java",image,"-XX:ActiveProcessorCount=2","-Xmx72m",
        "-Dsimpleplane.deviceCaFile=/fixtures/device.crt","-cp","/fixtures:/app/proxy.jar","com.proxy.remote.ProxyRemoteServer"])
    start_echo()
    print("OIDC and portal ready; isolated UDP relay started", flush=True)


if __name__=="__main__":
    if sys.argv[1]=="setup":setup()
    elif sys.argv[1]=="start-portal": start_portal()
    elif sys.argv[1]=="start-echo": start_echo()
    elif sys.argv[1]=="echo": echo()
    elif sys.argv[1] in {"cleanup","expire"}:
        cleanup()
        if sys.argv[1]=="expire":
            import shutil
            shutil.rmtree(directory)
    else:raise SystemExit("Unknown action")
