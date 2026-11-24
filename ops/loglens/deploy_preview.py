"""Deploy the log-center preview beside the existing subscription service."""

import argparse
import base64
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import time
from urllib.parse import parse_qs, urlsplit, urlunsplit

from ship import Elasticsearch

LIVE = Path("/opt/simpleplane-subscriptions")
BASE = Path("/opt/simpleplane-log-preview")
SITE = Path("/www/server/panel/vhost/nginx/simpleplane-subscriptions.conf")
LIVE_TEMPLATE = LIVE / "https.conf"
NGINX = "/www/server/nginx/sbin/nginx"
HOST = "212.64.14.155"
NAME = "simpleplane-log-preview"
PORT = 18182
NETWORK = "middleware-network"
CONTROL = "simpleplane-subscriptions_control"
INCLUDE = "    include /opt/simpleplane-log-preview/ingress/*.conf;\n"
ROUTES = ("/admin/logs-preview", "/admin/logs-preview/users", "/admin/logs-preview/search",
          "/admin/logs.css", "/admin/logs.js")
LOGIN_KEYS = ("SP_REDIS_URL", "SP_PUBLIC_ORIGIN", "SSO_ISSUER", "SSO_CLIENT_ID", "SSO_CLIENT_SECRET",
              "SP_ADMIN_SUBJECTS", "SSO_CLIENT_LOOKUP_HMAC_KEY", "SSO_CLIENT_REFRESH_AES_KEY")


def run(*args, input=None, timeout=30):
    result = subprocess.run(args, input=input, text=True, capture_output=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f"{Path(args[0]).name} failed ({result.returncode})")
    return result.stdout.strip()


def mysql(sql):
    return run("docker", "exec", "-i", "mysql-tmd", "sh", "-c",
               'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --batch --skip-column-names --user=root', input=sql)


def env_file(path):
    result = {}
    for line in path.read_text().splitlines():
        if not line or line.lstrip().startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        if value.startswith("'") and value.endswith("'"):
            value = value[1:-1]
        result[key] = value
    return result


def write(path, content, mode=0o600):
    temp = path.with_name("." + path.name + ".next")
    temp.write_text(content, encoding="utf-8")
    temp.chmod(mode)
    temp.replace(path)


def network_address(container, network):
    info = json.loads(run("docker", "inspect", container))[0]
    return info["NetworkSettings"]["Networks"][network]["IPAddress"]


def curl(route, public):
    if public:
        url = f"https://{HOST}{route}"
        extra = ("--cacert", str(LIVE / "pki/web-ca.crt"), "--resolve", f"{HOST}:443:127.0.0.1")
    else:
        url = f"http://127.0.0.1:{PORT}{route}"
        extra = ("-H", f"Host: {HOST}", "-H", "X-Forwarded-Proto: https")
    return run("curl", "-sS", "--max-time", "5", "-o", "/dev/null", "-w",
               "%{http_code} %{redirect_url}", *extra, url, timeout=8)


def check_login(route, public):
    result = curl(route, public)
    status, _, location = result.partition(" ")
    expected = urlsplit(location)
    if status != "302" or expected.hostname != HOST or expected.path != "/sso/login" or not expected.query:
        raise RuntimeError(f"login redirect did not pass for {route}")
    return expected.query


def release_state(release):
    return json.loads((release / "preview.json").read_text())


def seed_index(prefix, issuer):
    es = Elasticsearch("http://127.0.0.1:9200", prefix, canary=True)
    es.prepare()
    now = datetime.now(timezone.utc)
    rows = []
    for i in range(72):
        moment = now - timedelta(minutes=5 + i * 15)
        at = int(moment.timestamp() * 1000)
        subject = "preview-alice" if i % 2 else "preview-bob"
        account = hashlib.sha256((issuer + "\0" + subject).encode()).hexdigest()
        device = "9577dd13-03d0-4d87-ad78-994962606101" if i % 2 else "9577dd13-03d0-4d87-ad78-994962606102"
        site = ["api.example.test", "status.example.test", "assets.example.test"][i % 3]
        node = "preview-east" if i % 3 else "preview-west"
        session = f"preview-{i:03}"
        failure = i % 7 == 0 or 18 <= i <= 23
        proxy = {"node_id": node, "account_id": account, "device_id": device, "proxy_session_id": session}
        common = {"dataset": "connection", **proxy, "target_host": site, "target_port": 443,
                  "domain_suffix": [site, "example.test"], "transport": "http3",
                  "connection_id": f"preview-connection-{i:03}", "stream_id": str(i)}
        rows.append(dict(common, **{"@timestamp": at}, event_id=session + "-start", event_kind="SESSION_OPENED"))
        kind = "SESSION_CONNECT_FAILED" if failure else "SESSION_ACTIVE"
        rows.append(dict(common, **{"@timestamp": at + 260}, event_id=session + "-result", event_kind=kind))
        if not failure:
            rows.append(dict(common, **{"@timestamp": at + 2200}, event_id=session + "-close", event_kind="SESSION_CLOSED",
                             uplink_bytes=330 * (i + 2), downlink_bytes=2381 * (i + 1)))
        detail = "fixture connection timeout" if failure else "fixture connection established"
        rows.append(dict(proxy, **{"@timestamp": at + 280}, dataset="application", event_id=session + "-log",
                         message=detail, template=detail, level="WARN" if failure else "INFO",
                         logger="preview.proxy", **({"exception_type": "java.net.SocketTimeoutException"} if failure else {})))
    for offset in range(0, len(rows), 80):
        es.bulk([es.lines(row) for row in rows[offset:offset + 80]])
    es.request("POST", "/" + prefix + "-*/_refresh", None)
    count = es.request("GET", "/" + prefix + "-*/_count", None)["count"]
    if count < len(rows):
        raise RuntimeError("preview index verification failed")
    print(f"Seeded {count} synthetic events", flush=True)


def start(release):
    if run("docker", "ps", "-aq", "--filter", "name=^/" + NAME + "$"):
        raise RuntimeError("preview container already exists")
    if int(re.search(r"^MemAvailable:\s+(\d+)", Path("/proc/meminfo").read_text(), re.M)[1]) < 470 * 1024:
        raise RuntimeError("insufficient memory headroom for another portal")
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", PORT))
    source = env_file(LIVE / "config/portal.env")
    stamp = release.name[:12]
    schema = "sp_log_preview_" + stamp
    directory = schema + "_id"
    prefix = "sp-log-canary-preview-" + stamp
    path = release / "preview.json"
    if path.exists():
        state = release_state(release)
        if state != {"schema": schema, "directory": directory, "prefix": prefix} or not (release / "portal.env").is_file():
            raise RuntimeError("unexpected preview state")
    else:
        if mysql(f"SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME IN ('{schema}','{directory}');"):
            raise RuntimeError("preview database name already in use")
        password = secrets.token_hex(25)
        jdbc = urlsplit(source["SP_DB_URL"].removeprefix("jdbc:"))
        if jdbc.scheme != "mysql" or not jdbc.hostname or jdbc.username or jdbc.password:
            raise RuntimeError("unexpected live database URL format")
        env = {key: source[key] for key in LOGIN_KEYS}
        env.update(SP_DB_URL="jdbc:" + urlunsplit(jdbc._replace(path="/" + schema)),
                   SP_DB_USER=schema, SP_DB_PASSWORD=password,
                   SP_BIND_ADDRESS="0.0.0.0", SP_PORT=str(PORT), SP_IDENTITY_SCHEMA=directory,
                   SP_CHALLENGE_KEY=base64.b64encode(secrets.token_bytes(32)).decode(),
                   SP_LOG_ES_URL="http://" + network_address("es-tmd", NETWORK) + ":9200",
                   SP_LOG_ES_PREFIX=prefix, SP_LOG_OPERATORS=env["SP_ADMIN_SUBJECTS"],
                   SP_LOG_INSECURE_CANARY="true", SERVER_SERVLET_SESSION_COOKIE_NAME="SP_LOG_PREVIEW_SESSION",
                   SERVER_SERVLET_SESSION_COOKIE_SECURE="true", SERVER_FORWARD_HEADERS_STRATEGY="framework",
                   SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE="2", MALLOC_ARENA_MAX="2")
        if any("\n" in value or "\r" in value for value in env.values()):
            raise RuntimeError("invalid preview environment value")
        mysql(f"""
            CREATE DATABASE `{schema}` CHARACTER SET utf8mb4;
            CREATE DATABASE `{directory}` CHARACTER SET utf8mb4;
            CREATE USER '{schema}'@'%' IDENTIFIED BY '{password}';
            GRANT ALL PRIVILEGES ON `{schema}`.* TO '{schema}'@'%';
            GRANT SELECT ON `{directory}`.* TO '{schema}'@'%';
            CREATE TABLE `{directory}`.sp_subscription_directory (
                subject_id VARCHAR(128) PRIMARY KEY, username VARCHAR(128) NOT NULL,
                display_name VARCHAR(100) NOT NULL, enabled BOOLEAN NOT NULL
            );
            INSERT INTO `{directory}`.sp_subscription_directory VALUES
                ('preview-alice','alice-demo','测试用户 Alice',TRUE),
                ('preview-bob','bob-demo','测试用户 Bob',TRUE);
        """)
        write(release / "portal.env", "".join(f"{key}={value}\n" for key, value in env.items()))
        write(path, json.dumps({"schema": schema, "directory": directory, "prefix": prefix}))
    seed_index(prefix, source["SSO_ISSUER"])
    run("docker", "create", "--name", NAME, "--label", f"simpleplane.preview.release={release.name}", "--init",
        "--network", NETWORK, "-p", f"127.0.0.1:{PORT}:{PORT}", "--memory", "288m", "--memory-swap", "288m",
        "--oom-score-adj", "500", "--cpus", "0.8", "--pids-limit", "100", "--security-opt", "no-new-privileges",
        "--cap-drop", "ALL", "--read-only", "--tmpfs", "/tmp:rw,exec,nosuid,size=24m",
        "--log-opt", "max-size=3m", "--log-opt", "max-file=2", "--env-file", str(release / "portal.env"),
        "-v", str(release / "portal.jar") + ":/app/portal.jar:ro", "-v", str(LIVE / "pki/trust.p12") + ":/pki/trust.p12:ro",
        "--entrypoint", "java", "eclipse-temurin:17-jre-jammy", "-XX:ActiveProcessorCount=2", "-XX:+UseSerialGC",
        "-Xms24m", "-Xmx80m", "-XX:ReservedCodeCacheSize=27m", "-Xss512k",
        "-Djavax.net.ssl.trustStore=/pki/trust.p12", "-Djavax.net.ssl.trustStorePassword=changeit", "-jar", "/app/portal.jar")
    try:
        run("docker", "network", "connect", CONTROL, NAME)
        run("docker", "start", NAME)
        for _ in range(50):
            try:
                login = check_login(ROUTES[0], public=False)
                if parse_qs(login).get("returnTo") == [ROUTES[0]]:
                    break
            except RuntimeError:
                pass
            time.sleep(1)
        else:
            raise RuntimeError("candidate did not reach protected readiness")
        mysql(f"""
            INSERT INTO `{schema}`.sp_node(node_id,label,host,port,server_name,enabled,configured) VALUES
                 ('preview-east','灰度节点 East','east.example.test',9090,'east.example.test',TRUE,TRUE),
                 ('preview-west','灰度节点 West','west.example.test',9090,'west.example.test',TRUE,TRUE)
            ON DUPLICATE KEY UPDATE label=VALUES(label);
        """)
        print(f"Preview portal ready at 127.0.0.1:{PORT}", flush=True)
    except Exception:
        state = json.loads(run("docker", "inspect", NAME))[0]["State"]
        print(f"Preview container state: {state['Status']}, OOM={state['OOMKilled']}", flush=True)
        raise


def publish(release):
    check_login(ROUTES[0], public=False)
    check_login("/admin/grants", public=True)
    state = json.loads(run("docker", "inspect", NAME))[0]
    if state["Config"]["Labels"].get("simpleplane.preview.release") != release.name:
        raise RuntimeError("unexpected preview container")
    original = SITE.read_text()
    desired = LIVE_TEMPLATE.read_text()
    if original != desired or "location = / {" not in original:
        raise RuntimeError("production virtual host differs from the managed template")
    ingress = BASE / "ingress"
    ingress.mkdir(exist_ok=True)
    fragment = ingress / "logs-preview.conf"
    if INCLUDE in original or fragment.exists():
        raise RuntimeError("preview ingress is already configured")
    write(release / "nginx.before", original)
    locations = "".join(f"    location = {route} {{ proxy_pass http://127.0.0.1:{PORT}; }}\n" for route in ROUTES)
    after = original.replace("    location = / {", INCLUDE + "    location = / {", 1)
    if after == original:
        raise RuntimeError("managed insertion point not found")
    try:
        write(fragment, locations, 0o644)
        write(SITE, after, 0o644)
        run(NGINX, "-t")
        run(NGINX, "-s", "reload")
        for _ in range(10):
            try:
                check_login(ROUTES[0], public=True)
                check_login("/admin/grants", public=True)
                break
            except RuntimeError:
                time.sleep(1)
        else:
            raise RuntimeError("preview or production route verification failed")
        write(LIVE_TEMPLATE, after, 0o644)
    except Exception:
        write(SITE, original, 0o644)
        fragment.unlink(missing_ok=True)
        run(NGINX, "-t")
        run(NGINX, "-s", "reload")
        raise
    print(f"Preview is protected at https://{HOST}{ROUTES[0]}", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("start", "publish"))
    args = parser.parse_args()
    release = Path(__file__).resolve().parent
    if release.parent != BASE or not re.fullmatch(r"[0-9a-f]{64}", release.name):
        raise SystemExit("unexpected preview release directory")
    with (release / "portal.jar").open("rb") as artifact:
        if hashlib.file_digest(artifact, "sha256").hexdigest() != release.name:
            raise SystemExit("preview artifact checksum mismatch")
    os.umask(0o077)
    {"start": start, "publish": publish}[args.action](release)
