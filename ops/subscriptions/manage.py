"""Operator commands for the permanent subscription deployment on the management host."""
import base64
import fcntl
import json
import os
from pathlib import Path
import pty
import re
import secrets
import select
import subprocess
import sys
import time
import uuid

ROOT = Path("/opt/simpleplane-subscriptions")
ORIGIN = "https://212.64.14.155"
DB = "simpleplane_subscriptions"
CLIENT = "simpleplane-subscriptions"
SECRETS = Path("/opt/authsystem-simple-sso/secrets")
SITE = Path("/www/server/panel/vhost/nginx/simpleplane-subscriptions.conf")
NGINX = "/www/server/nginx/sbin/nginx"
JAVA = "eclipse-temurin:17-jre-jammy"


def run(args, *, data=None, env=None):
    result = subprocess.run(args, input=data, text=True, capture_output=True, env=env, timeout=180)
    if result.returncode:
        # Tool output may contain SQL, passwords or OAuth parameters.
        raise RuntimeError(f"{Path(args[0]).name} exited {result.returncode}")
    return result.stdout.strip()


def write(path, value, mode=0o600):
    path = Path(path)
    tmp = path.with_name("." + path.name + ".new")
    tmp.write_text(value)
    tmp.chmod(mode)
    tmp.replace(path)


def settings():
    return json.loads(Path("config/installation.json").read_text())


def save(values):
    write("config/installation.json", json.dumps(values))


def sql(query):
    return run(["docker","exec","-i","mysql-tmd","sh","-c",
                'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -B -N -uroot'], data=query)


def auth_env():
    cfg = json.loads(run(["docker","inspect","authsystem-sso-server"]))[0]["Config"]
    return dict(item.split("=",1) for item in cfg["Env"])


def env_file(name, values):
    if any("\n" in str(v) or "\r" in str(v) or "'" in str(v) for v in values.values()):
        raise ValueError("Unexpected character in service environment")
    write("config/" + name + ".env", "".join(f"{k}='{v}'\n" for k,v in values.items()))


def openssl(*args, env=None):
    return run(["openssl",*args], env=env)


def cert_authority(prefix, cn):
    key, cert = prefix+".key", prefix+".crt"
    if not Path(key).exists():
        if Path(cert).exists(): raise RuntimeError("Missing CA private key")
        openssl("ecparam","-name","prime256v1","-genkey","-noout","-out",key)
    if not Path(cert).exists():
        openssl("req","-new","-x509","-sha256","-days","3650","-key",key,"-out",cert,
                "-subj","/CN="+cn,"-addext","basicConstraints=critical,CA:TRUE,pathlen:0",
                "-addext","keyUsage=critical,keyCertSign,cRLSign")


def renew():
    key = Path("relay/tls.key")
    if not key.exists():
        openssl("genpkey","-algorithm","EC","-pkeyopt","ec_paramgen_curve:prime256v1","-out",str(key))
    elif key.read_bytes().startswith(b"-----BEGIN EC PRIVATE KEY-----"):
        # Netty expects PKCS#8. Preserve the key pair already used by nginx.
        converted = key.with_suffix(".next.key")
        openssl("pkcs8","-topk8","-nocrypt","-in",str(key),"-out",str(converted))
        converted.replace(key)
    cert = Path("relay/tls.crt")
    if cert.exists() and subprocess.run(["openssl","x509","-in",str(cert),"-checkend","2592000","-noout"],
                                        stdout=subprocess.DEVNULL).returncode == 0:
        return False
    openssl("req","-new","-key","relay/tls.key","-out","pki/server.csr","-subj","/CN=212.64.14.155")
    write("pki/server.ext","basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature\n"
          "extendedKeyUsage=serverAuth\nsubjectAltName=IP:212.64.14.155\n")
    openssl("x509","-req","-in","pki/server.csr","-CA","pki/web-ca.crt","-CAkey","pki/web-ca.key",
            "-set_serial",str(secrets.randbits(150)+1),"-days","365","-sha256","-extfile","pki/server.ext",
            "-out","relay/tls.next.crt")
    openssl("verify","-CAfile","pki/web-ca.crt","-verify_ip","212.64.14.155","relay/tls.next.crt")
    Path("relay/tls.next.crt").replace(cert)
    return True


def catalog_nodes():
    inventory = json.loads(Path("standard-nodes.json").read_text())
    active_file = Path("config/published-nodes.json")
    active = json.loads(active_file.read_text()) if active_file.exists() else []
    known = {node["id"] for node in inventory}
    if not isinstance(active,list) or len(set(active)) != len(active) or set(active) - known or len(known) != len(inventory):
        raise ValueError("Published node does not match the standard group inventory")
    secrets_by_id = json.loads(Path("config/node-secrets.json").read_text()) if active else {}
    nodes = []
    for node in inventory:
        if node["id"] in active:
            ident=node["id"]
            params=secrets_by_id[ident]
            directory=Path("pki/catalog")/ident
            if not all((directory/name).is_file() for name in ("device.p12","server.crt")):
                raise RuntimeError("Missing certificate materials for "+ident)
            nodes.append(dict(id=ident, name=node["name"], groupId="standard", host=node["host"],
                              serverName=node["host"], port=node["port"],
                              serverCa=f"/pki/catalog/{ident}/server.crt",signerAlias=ident,
                              signerStore=f"/pki/catalog/{ident}/device.p12",
                              signerPassword=params["signerPassword"],cipherKey=params["cipherKey"]))
    return nodes


def prepare_node_issuers():
    inventory = json.loads(Path("standard-nodes.json").read_text())
    state = Path("config/node-secrets.json")
    secrets_by_id = json.loads(state.read_text()) if state.exists() else {}
    for n in inventory:
        ident = n["id"]
        if not re.fullmatch("[a-z0-9-]+", ident):
            raise ValueError("Invalid node identifier")
        public = Path("pki/catalog") / ident
        private = Path("pki/issuers") / ident
        for path in (public, private):
            path.mkdir(parents=True,exist_ok=True)
        if ident not in secrets_by_id:
            if any(private.iterdir()) or any(public.iterdir()):
                raise RuntimeError("Existing node materials need operator recovery")
            secrets_by_id[ident] = {"cipherKey":secrets.token_hex(16),"signerPassword":secrets.token_urlsafe(32)}
            write(state,json.dumps(secrets_by_id))
        cert_authority(str(private/"device"),ident+" device CA")
        if not (public/"device.p12").exists():
            openssl("pkcs12","-export","-name",ident,"-inkey",str(private/"device.key"),"-in",str(private/"device.crt"),
                    "-out",str(public/"device.next.p12"),"-passout","env:STORE_PASS",
                    env=dict(os.environ,STORE_PASS=secrets_by_id[ident]["signerPassword"]))
            (public/"device.next.p12").replace(public/"device.p12")


def publish_node(ident):
    inventory = json.loads(Path("standard-nodes.json").read_text())
    if ident not in {node["id"] for node in inventory}:
        raise ValueError("Unknown node")
    ca = Path("pki/catalog")/ident/"server.crt"
    run(["openssl","x509","-in",str(ca),"-checkend","0","-noout"])
    path = Path("config/published-nodes.json")
    previous = json.loads(path.read_text()) if path.exists() else []
    prior_env = Path("config/portal.env").read_text()
    def reload_portal():
        run(["docker","compose","up","-d","--pull","never","--no-deps","--force-recreate","portal"])
        for _ in range(45):
            result=subprocess.run(["curl","--noproxy","*","--cacert","pki/root.crt","--resolve",
                   "212.64.14.155:443:127.0.0.1","-s","-o","/dev/null","-w","%{http_code}",
                   "--max-time","3",ORIGIN+"/admin/grants"],capture_output=True,text=True)
            if result.stdout=="302":
                return
            time.sleep(1)
        raise RuntimeError("Subscription portal did not become ready")
    try:
        write(path,json.dumps(sorted(set(previous)|{ident})))
        configure(settings())
        reload_portal()
    except Exception:
        write(path,json.dumps(previous))
        write("config/portal.env",prior_env)
        reload_portal()
        raise


def configure(v):
    old = auth_env()
    env_file("identity",{
        "SSO_DB_URL":old["SSO_DB_URL"],"SSO_DB_USERNAME":old["SSO_DB_USERNAME"],
        "SPRING_CONFIG_IMPORT":"configtree:/run/secrets/","SSO_ISSUER":ORIGIN,"SSO_COOKIE_SECURE":"true",
        "SSO_OIDC_KEYSTORE_PATH":old["SSO_OIDC_KEYSTORE_PATH"],"SSO_OIDC_KEY_ALIAS":old["SSO_OIDC_KEY_ALIAS"],
        "SSO_HTTP_PORT":"8080","SSO_IDENTITY_CREDENTIAL_MODE":"database","SPRING_FLYWAY_ENABLED":"false",
        "SSO_REDIS_HOST":"sessions","SSO_REDIS_PASSWORD":v["redis"],"SERVER_FORWARD_HEADERS_STRATEGY":"framework",
        "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE":"4","NACOS_ADDRESS":"N/A",
        "DUBBO_REGISTRY_USE_AS_CONFIG_CENTER":"false","DUBBO_REGISTRY_USE_AS_METADATA_CENTER":"false"})
    # Only the selected account's immutable subject is granted administrative access.
    if not v.get("client_secret"): return
    env_file("portal",{
        "SP_DB_URL":"jdbc:mysql://mysql-tmd:3306/"+DB+"?sslMode=PREFERRED&allowPublicKeyRetrieval=true&connectionTimeZone=UTC",
        "SP_DB_USER":DB,"SP_DB_PASSWORD":v["database"],"SP_REDIS_URL":"redis://:"+v["redis"]+"@sessions:6379",
        "SP_BIND_ADDRESS":"0.0.0.0","SP_PUBLIC_ORIGIN":ORIGIN,"SSO_ISSUER":ORIGIN,"SSO_CLIENT_ID":CLIENT,
        "SSO_CLIENT_SECRET":v["client_secret"],"SP_ADMIN_SUBJECTS":v["subject"],"SP_CHALLENGE_KEY":v["challenge"],
        "SSO_CLIENT_LOOKUP_HMAC_KEY":v["lookup"],"SSO_CLIENT_REFRESH_AES_KEY":v["refresh"],
        "SERVER_SERVLET_SESSION_COOKIE_NAME":"SPPORTAL","SERVER_SERVLET_SESSION_COOKIE_SECURE":"true",
        "SERVER_FORWARD_HEADERS_STRATEGY":"framework",
        "SPRING_APPLICATION_JSON":json.dumps({"subscription":{"catalog":{"nodes":catalog_nodes()}}})})


def prepare():
    for directory in ["config","pki","relay"]: Path(directory).mkdir(exist_ok=True)
    if not Path("config/installation.json").exists():
        if sql(f"SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='{DB}';"
               f"SELECT User FROM mysql.user WHERE User='{DB}';"):
            raise RuntimeError("Existing unmanaged subscription database or SQL account")
        v = {key:secrets.token_urlsafe(32) for key in ["database","device","redis"]}
        for key in ["challenge","lookup","refresh"]: v[key]=base64.b64encode(os.urandom(32)).decode()
        v.update(frame=secrets.token_hex(16),subject=str(uuid.uuid4()))
        save(v)
    v=settings()
    sql(f"CREATE DATABASE IF NOT EXISTS {DB} CHARACTER SET utf8mb4; "
        f"CREATE USER IF NOT EXISTS '{DB}'@'%' IDENTIFIED BY '{v['database']}'; "
        f"GRANT ALL ON {DB}.* TO '{DB}'@'%';")
    cert_authority("pki/web-ca","SimplePlane private web CA")
    write("pki/root.crt", Path("pki/web-ca.crt").read_text(), 0o644)
    renew()
    cert_authority("pki/device-ca","SimplePlane cn-01 device CA")
    write("relay/device.crt",Path("pki/device-ca.crt").read_text(),0o644)
    if not Path("pki/device.p12").exists():
        openssl("pkcs12","-export","-name","cn-01","-inkey","pki/device-ca.key","-in","pki/device-ca.crt",
                "-out","pki/device.p12","-passout","env:STORE_PASS",env=dict(os.environ,STORE_PASS=v["device"]))
    if not Path("pki/trust.p12").exists():
        run(["docker","run","--rm","--network","none","--memory","100m","-v",str(ROOT/"pki")+":/work",
             "--entrypoint","keytool",JAVA,"-importcert","-noprompt","-alias","web","-file","/work/root.crt",
             "-keystore","/work/trust.p12","-storetype","PKCS12","-storepass","changeit"])
    write("config/redis.conf","bind 0.0.0.0\nprotected-mode yes\nrequirepass "+v["redis"]+
          "\nappendonly yes\nmaxmemory 20mb\nmaxmemory-policy noeviction\ndir /data\n")
    uid=int(run(["docker","run","--rm","--network","none","--memory","32m","--entrypoint","id","redis:7-alpine","-u","redis"]))
    os.chown("config/redis.conf",uid,-1)
    write("relay/remote.yml","host: 0.0.0.0\nport: 9443\ntransport: http3\ncipher: aes-gcm-v2\ncipherKey: '"+
          v["frame"]+"'\nworkerThreads: 1\nbizThreads: 3\nhttp3:\n  certificateFile: /relay/tls.crt\n"
          "  privateKeyFile: /relay/tls.key\n  maxConnections: 48\n  maxStreamsPerConnection: 64\n")
    configure(v)


def admin_cli(args, password=None):
    # The official CLI reads passwords from a TTY and generates OIDC client secrets exactly once.
    old=auth_env()
    env=dict(os.environ,SSO_DB_URL=old["SSO_DB_URL"],SSO_DB_USERNAME=old["SSO_DB_USERNAME"],
             SSO_DB_PASSWORD=(SECRETS/"SSO_DB_PASSWORD").read_text().strip(),SSO_ADMIN_ACTOR="simpleplane-operator")
    master,slave=pty.openpty()
    cmd=["docker","run","--rm","-it","--log-driver","none","--network","middleware-network","--memory","180m",
         "--cpus","1","-v",str(ROOT/"sso-admin.jar")+":/app/admin.jar:ro"]
    for key in ("SSO_DB_URL","SSO_DB_USERNAME","SSO_DB_PASSWORD","SSO_ADMIN_ACTOR"): cmd.extend(["-e",key])
    cmd+=["--entrypoint","java",JAVA,"-XX:ActiveProcessorCount=1","-Xmx72m","-jar","/app/admin.jar",*args]
    with subprocess.Popen(cmd,stdin=slave,stdout=slave,stderr=slave,env=env) as proc:
        os.close(slave)
        output=bytearray(); sent=0; deadline=time.monotonic()+75
        try:
            while time.monotonic()<deadline:
                if not select.select([master],[],[],0.5)[0]:
                    if proc.poll() is not None: break
                    continue
                try: block=os.read(master,4096)
                except OSError: break
                if not block: break
                output.extend(block)
                if password and sent<2 and [b"characters):",b"Confirm password:"][sent] in output:
                    os.write(master,password.encode()+b"\n"); sent+=1
            if proc.wait(timeout=5)!=0: raise RuntimeError("Official SSO provisioning command failed")
        finally:
            os.close(master)
            if proc.poll() is None: proc.kill()
    if password: return
    match=re.search(rb"once\):\s*([A-Za-z0-9_-]{43})",output)
    if not match: raise RuntimeError("SSO did not return a confidential client credential")
    return match.group(1).decode()


def identities():
    v=settings()
    password=sys.stdin.readline(1026).strip()
    if not 12<=len(password)<=1024: raise ValueError("Expected the operator's protected initial password on stdin")
    subject=sql("SELECT subject_id FROM sso_authsystem_v2.sso_user WHERE username_normalized='zhh'")
    if subject and subject!=v["subject"]: raise RuntimeError("Account ownership mismatch")
    if not subject: admin_cli(["user","create","--username","zhh","--subject",v["subject"],"--display-name","zhh"],password)
    if not v.get("client_secret"):
        if sql("SELECT client_id FROM sso_authsystem_v2.oauth_client WHERE client_id='"+CLIENT+"'"):
            raise RuntimeError("Existing OAuth client requires operator recovery")
        v["client_secret"]=admin_cli(["client","create","--id",CLIENT,"--name","SimplePlane Subscriptions","--type",
                                     "CONFIDENTIAL","--redirect-uri",ORIGIN+"/sso/callback","--scope","openid"])
        save(v)
    configure(v)


def ingress():
    desired=Path("https.conf").read_text()
    if SITE.exists() and SITE.read_text()!=desired: raise RuntimeError("Existing nginx site needs explicit reconciliation")
    if not SITE.exists():
        if run(["ss","-Hlnt","sport = :443"]): raise RuntimeError("TCP 443 already in use")
        write(SITE,desired,0o644)
        try: run([NGINX,"-t"])
        except Exception:
            SITE.unlink()
            raise
    run([NGINX,"-s","reload"])


if __name__=="__main__":
    if Path(__file__).resolve().parent!=ROOT or os.getuid()!=0: sys.exit("Run from the managed installation directory as root")
    os.chdir(ROOT)
    os.umask(0o077)
    with open(".deploy.lock","a") as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        action=sys.argv[1]
        if action=="prepare": prepare()
        elif action=="identities": identities()
        elif action=="ingress": ingress()
        elif action=="renew":
            if renew():
                run([NGINX,"-t"]); run([NGINX,"-s","reload"])
                run(["docker","compose","restart","relay"])
        elif action=="publish" and len(sys.argv)==3: publish_node(sys.argv[2])
        elif action=="node-issuers": prepare_node_issuers()
        elif action=="console-identity": sql(Path("console-identity.sql").read_text())
        else: sys.exit("Unknown operator command")
    print(action+" completed",flush=True)
