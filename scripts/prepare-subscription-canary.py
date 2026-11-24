"""Create a private, disposable test fixture and provision isolated remote services.

The target host runs scripts/subscription-canary-host.py. A separate cleanup
command removes only this run's fixtures. Never place the run directory in Git.
"""
import argparse
import base64
import datetime as dt
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import shlex
import shutil
import subprocess
import tempfile

from argon2 import PasswordHasher
import bcrypt
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, rsa
from cryptography.hazmat.primitives.serialization import pkcs12
from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID

ROOT = Path(__file__).resolve().parents[1]
IP = "212.64.14.155"
ORIGIN = "https://" + IP
TARGET = "root@" + IP


def certificate(name, key, issuer=None, purpose=None):
    subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,name)])
    now=dt.datetime.now(dt.timezone.utc)
    builder=(x509.CertificateBuilder().subject_name(subject).issuer_name(issuer[1].subject if issuer else subject)
             .public_key(key.public_key()).serial_number(x509.random_serial_number())
             .not_valid_before(now-dt.timedelta(minutes=2)).not_valid_after(now+dt.timedelta(hours=6))
             .add_extension(x509.BasicConstraints(issuer is None,0 if issuer is None else None),True))
    if purpose:
        builder=builder.add_extension(x509.ExtendedKeyUsage([purpose]),False)
        builder=builder.add_extension(x509.SubjectAlternativeName([x509.IPAddress(ipaddress.ip_address(IP))]),False)
    return builder.sign(issuer[0] if issuer else key,hashes.SHA256())


def prepare(path):
    def token(): return secrets.token_urlsafe(32)
    def aes(): return base64.b64encode(secrets.token_bytes(32)).decode()
    def save_cert(name, cert): (path/name).write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    def store(name, alias, key, cert, password):
        (path/name).write_bytes(pkcs12.serialize_key_and_certificates(alias.encode(),key,cert,None,
                                serialization.BestAvailableEncryption(password.encode())))
    root=ec.generate_private_key(ec.SECP256R1())
    trust=certificate("SimplePlane isolated acceptance",root)
    server=ec.generate_private_key(ec.SECP256R1())
    server_cert=certificate(IP,server,(root,trust),ExtendedKeyUsageOID.SERVER_AUTH)
    save_cert("root.crt",trust);save_cert("https.crt",server_cert)
    (path/"https.key").write_bytes(server.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,
                                                        serialization.NoEncryption()))
    device=ec.generate_private_key(ec.SECP256R1())
    device_ca=certificate("Canary node A device CA",device)
    device_pass,oidc_pass=token(),token()
    save_cert("device.crt",device_ca);store("device.p12","node-a",device,device_ca,device_pass)
    oidc=rsa.generate_private_key(public_exponent=65537,key_size=2048)
    store("oidc.p12","sso-signing",oidc,certificate("Canary OIDC",oidc),oidc_pass)
    frame=secrets.token_hex(16)
    (path/"remote.yml").write_text(f"""host: 0.0.0.0
port: 443
transport: http3
cipher: aes-gcm-v2
cipherKey: '{frame}'
workerThreads: 1
bizThreads: 3
http3:
  certificateFile: /fixtures/https.crt
  privateKeyFile: /fixtures/https.key
  maxConnections: 24
  maxStreamsPerConnection: 32
""")
    client_secret=token()
    username="spgray"+secrets.token_hex(4)
    subject=username+"-subject"
    password=token()
    oauth="sp-canary"
    password_hash=PasswordHasher(time_cost=2,memory_cost=16384,parallelism=1).hash(password)
    secret_hash="{bcrypt}"+bcrypt.hashpw(client_secret.encode(),bcrypt.gensalt()).decode()
    (path/"browser.json").write_text(json.dumps(dict(username=username,subject=subject,password=password)))
    (path/"seed.sql").write_text(f"""
INSERT INTO sso_user(subject_id,username_normalized,password_hash,enabled,display_name,email_verified)
VALUES('{subject}','{username}','{password_hash}',true,'Canary admin',false);
INSERT INTO oauth_client(client_id,display_name,client_type,enabled,client_secret_hash)
VALUES('{oauth}','SimplePlane Canary','CONFIDENTIAL',true,'{secret_hash}');
INSERT INTO oauth_client_redirect_uri(client_id,redirect_uri) VALUES('{oauth}','{ORIGIN}/sso/callback');
INSERT INTO oauth_client_scope(client_id,scope_name) VALUES('{oauth}','openid');
""")
    catalog=dict(id="canary-a",groupId="test",name="Canary A",host=IP,port=443,serverName=IP,
                 serverCa="/fixtures/root.crt",signerStore="/fixtures/device.p12",signerAlias="node-a",
                 signerPassword=device_pass,cipherKey=frame)
    settings=dict(sso_db_password=token(),rp_db_password=token(),
        sso_env=dict(SSO_HTTP_PORT="18080",SSO_ISSUER=ORIGIN,SSO_COOKIE_SECURE="true",
            SSO_REDIS_HOST="127.0.0.1",SSO_REDIS_PORT="6379",SSO_IDENTITY_CREDENTIAL_MODE="database",
            SSO_OIDC_KEYSTORE_PATH="/fixtures/oidc.p12",SSO_OIDC_KEYSTORE_PASSWORD=oidc_pass,SSO_OIDC_KEY_ALIAS="sso-signing",
            SSO_SESSION_HMAC_KEY=token(),SSO_RATE_LIMIT_HMAC_KEY=token(),SSO_OAUTH_STATE_HMAC_KEY=token(),
            SSO_TOKEN_STORAGE_HMAC_KEY=token(),NACOS_ADDRESS="N/A",DUBBO_REGISTRY_USE_AS_CONFIG_CENTER="false",
            DUBBO_REGISTRY_USE_AS_METADATA_CENTER="false",SERVER_FORWARD_HEADERS_STRATEGY="framework"),
        rp_env=dict(SP_PUBLIC_ORIGIN=ORIGIN,SSO_ISSUER=ORIGIN,SSO_CLIENT_ID=oauth,SSO_CLIENT_SECRET=client_secret,
            SP_ADMIN_SUBJECTS=subject,SP_CHALLENGE_KEY=aes(),SSO_CLIENT_LOOKUP_HMAC_KEY=aes(),SSO_CLIENT_REFRESH_AES_KEY=aes(),
            SP_REDIS_URL="redis://127.0.0.1:6379",SERVER_SERVLET_SESSION_COOKIE_NAME="SPPORTAL",SERVER_SERVLET_SESSION_COOKIE_SECURE="true",
            SERVER_FORWARD_HEADERS_STRATEGY="framework",SPRING_APPLICATION_JSON=json.dumps({"subscription":{"catalog":{"nodes":[catalog]}}})))
    (path/"fixture.json").write_text(json.dumps(settings))


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("action",choices=["setup","cleanup","status","seed-viewer"])
    parser.add_argument("--key",required=True)
    parser.add_argument("--run",type=Path,help="Private run directory for status or cleanup")
    args=parser.parse_args()
    opts=["-i",args.key,"-o","BatchMode=yes","-o","IdentitiesOnly=yes","-o","StrictHostKeyChecking=yes","-o","UpdateHostKeys=no"]
    def ssh(command):
        return subprocess.check_output(["ssh",*opts,TARGET,command],text=True,timeout=360).strip()
    if args.action=="setup":
        run_id="sp-sub-"+secrets.token_hex(6)
        work=Path(tempfile.mkdtemp(prefix=run_id+"-"))
        (work/"run-id").write_text(run_id)
    else:
        work=args.run.resolve()
        run_id=(work/"run-id").read_text()
    if not re.fullmatch("sp-sub-[0-9a-f]{12}",run_id): raise ValueError("Invalid fixture")
    remote="/var/tmp/"+run_id
    script=remote+"/host.py"
    if args.action=="setup":
        print("Private run directory:",work,flush=True)
        prepare(work)
        ssh("umask 077 && mkdir "+remote)
        files={"host.py":ROOT/"scripts/subscription-canary-host.py",
               "console-identity.sql":ROOT/"ops/subscriptions/console-identity.sql",
               "traffic.py":ROOT/"scripts/subscription_canary_traffic.py",
               "client.jar":ROOT/"proxy-local/target/proxy-local-1.0.0-SNAPSHOT.jar",
               "proxy.jar":ROOT/"proxy-remote/target/proxy-remote-1.0.0-SNAPSHOT.jar",
               "portal.jar":ROOT/"subscription-server/target/subscription-server-1.0.0-SNAPSHOT.jar"}
        files.update({n:work/n for n in ["fixture.json","seed.sql","remote.yml","oidc.p12","device.p12","device.crt","root.crt","https.crt","https.key"]})
        for name,file in files.items():
            subprocess.run(["scp",*opts,str(file),TARGET+":"+remote+"/"+name],check=True,timeout=300)
            if ssh("sha256sum "+remote+"/"+name).split()[0]!=hashlib.sha256(file.read_bytes()).hexdigest():
                raise RuntimeError("Upload verification failed")
        print(ssh("python3 "+script+" setup"),flush=True)
    if args.action in {"setup","seed-viewer"}:
        register_viewer(work, run_id, opts)
    elif args.action=="status":
        print(ssh("docker ps -a --filter label=simpleplane.canary="+run_id+" --format '{{.Names}} {{.Status}}'"))
    else:
        print(ssh("if test -f "+script+"; then python3 "+script+" expire; fi"))
        ssh("if systemctl is-active --quiet "+run_id+"-expiry.timer; then systemctl stop "+run_id+"-expiry.timer; fi")
        if work.parent != Path(tempfile.gettempdir()).resolve() or not work.name.startswith(run_id + "-"):
            raise ValueError("Unexpected local fixture directory")
        shutil.rmtree(work)
        print("Private local fixture removed")


def register_viewer(work, run_id, opts):
    file = work / "browser.json"
    credentials = json.loads(file.read_text())
    if "viewer" in credentials:
        return
    name = "spviewer" + secrets.token_hex(6)
    password = secrets.token_urlsafe(32)
    encoded = PasswordHasher(time_cost=2,memory_cost=16384,parallelism=1).hash(password)
    schema = "spg" + run_id[-12:] + "_sso"
    sql = (f"INSERT INTO sso_user(subject_id,username_normalized,password_hash,enabled,display_name,email_verified) "
           f"VALUES('{name}','{name}','{encoded}',true,'Canary viewer',false);")
    command = ("docker exec -i mysql-tmd sh -c " +
               shlex.quote('MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --user=root ' + schema))
    subprocess.run(["ssh", *opts, TARGET, command], input=sql, text=True, check=True, timeout=20)
    credentials["viewer"] = {"username":name, "password":password, "subject":name}
    file.write_text(json.dumps(credentials))


if __name__=="__main__": main()
