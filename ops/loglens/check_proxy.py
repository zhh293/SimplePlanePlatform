"""Exercise a disposable LogLens relay and client in a private Docker network namespace."""

import base64
import contextlib
import json
import os
from pathlib import Path
import re
import secrets
import socket
import socketserver
import struct
import subprocess
import sys
import threading
import time
import uuid

ROOT = Path(__file__).resolve().parent
TARGET = "192.0.2.73"
ECHO_PORT = 28371
RELAY = ROOT.name + "-relay"
CLIENT = ROOT.name + "-client"
IMAGE = "eclipse-temurin:17-jre-jammy"
ACCOUNTS = ("a" * 64, "b" * 64)
NATIVE = os.environ.get("SP_LOG_CANARY_NATIVE") == "1"


def run(*words):
    return subprocess.run(words, check=True, text=True, capture_output=True, timeout=35).stdout.strip()


def openssl(*words):
    run("openssl", *words)


@contextlib.contextmanager
def java_process(name, folder, options, environment=None, script=None, memory="200m"):
    log = ROOT / (name + ".log")
    if NATIVE:
        command = ["java", *options] if script is None else ["sh", "-c", script]
        environment = dict(os.environ, **(environment or {}))
        with log.open("wb") as output, subprocess.Popen(command, cwd=folder, env=environment, stdout=output,
                                                         stderr=subprocess.STDOUT) as process:
            try:
                yield process.pid, lambda: log.read_text(errors="replace"), process.terminate
            finally:
                if process.poll() is None:
                    process.terminate()
                    with contextlib.suppress(subprocess.TimeoutExpired):
                        process.wait(8)
                    if process.poll() is None:
                        process.kill()
        return
    env_args = [part for key, value in (environment or {}).items() for part in ("-e", key + "=" + value)]
    workdir = "/work/" + str(folder.relative_to(ROOT))
    run("docker", "run", "-d", "--name", name, "--network", "none" if name == RELAY else "container:" + RELAY,
        "--memory", memory, "--memory-swap", memory, "--cpus", "1", "--cap-drop", "ALL", "--pids-limit", "96",
        "-v", f"{ROOT}:/work", "-w", workdir, *env_args, "--entrypoint", "sh" if script else "java", IMAGE,
        *(["-c", script] if script else options))
    try:
        pid = int(run("docker", "inspect", "--format={{.State.Pid}}", name))
        yield pid, lambda: run("docker", "logs", "--tail", "60", name), lambda: run("docker", "stop", "-t", "10", name)
    finally:
        with contextlib.suppress(subprocess.CalledProcessError):
            run("docker", "rm", "-f", name)


def certificate(directory, name, cn, ca=None, extension=None):
    stem = str(directory / name)
    if ca is None:
        openssl("req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1", "-nodes",
                "-days", "1", "-subj", "/CN=" + cn, "-keyout", stem + ".key", "-out", stem + ".crt")
    else:
        openssl("req", "-new", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1", "-nodes",
                "-subj", "/CN=" + cn, "-keyout", stem + ".key", "-out", stem + ".csr")
        Path(stem + ".ext").write_text(extension)
        openssl("x509", "-req", "-in", stem + ".csr", "-CA", str(ca) + ".crt", "-CAkey", str(ca) + ".key",
                "-set_serial", str(secrets.randbits(96)), "-days", "1", "-extfile", stem + ".ext", "-out", stem + ".crt")


def echo():
    class Tcp(socketserver.BaseRequestHandler):
        def handle(self):
            self.request.sendall(self.request.recv(1024))

    class Udp(socketserver.BaseRequestHandler):
        def handle(self):
            data, server = self.request
            server.sendto(data, self.client_address)

    with socketserver.TCPServer((TARGET, ECHO_PORT), Tcp) as tcp, socketserver.UDPServer((TARGET, ECHO_PORT), Udp) as udp:
        threading.Thread(target=udp.serve_forever, daemon=True).start()
        print("READY", flush=True)
        tcp.serve_forever()


def socks(port, command, target_port):
    source = ("127.0.0.2", 0) if command == 0xf0 else None
    with socket.create_connection(("127.0.0.1", port), timeout=6, source_address=source) as channel, channel.makefile("rb") as reader:
        channel.sendall(bytes.fromhex("050100"))
        assert reader.read(2) == bytes.fromhex("0500")
        channel.sendall(bytes([5, command, 0, 1]) + socket.inet_aton(TARGET) + struct.pack("!H", target_port))
        reply = reader.read(10)
        if target_port != ECHO_PORT:
            assert reply[1:2] not in (b"", b"\0")
            return
        assert reply[:4] == bytes([5, 0, 0, 1]), reply
        mark = secrets.token_bytes(21)
        packet = mark if command == 1 else struct.pack("!H", len(mark)) + mark
        channel.sendall(packet)
        assert reader.read(len(packet)) == packet


def client(folder_label, cipher, cipher_key, device):
    folder = ROOT / folder_label
    folder.mkdir()
    with socket.socket() as port_socket:
        port_socket.bind(("127.0.0.1", 0))
        port = port_socket.getsockname()[1]
    work = str(ROOT) if NATIVE else "/work"
    http3 = {"serverName": "localhost", "caFile": work + "/server-ca.crt"}
    if device:
        cn = str(uuid.uuid4())
        certificate(folder, "device", cn, ROOT / "device-ca",
                    "extendedKeyUsage=clientAuth\nsubjectAltName=URI:https://simpleplane.invalid/account/" + device + "\n")
        openssl("pkcs8", "-topk8", "-nocrypt", "-in", str(folder / "device.key"), "-outform", "DER",
                "-out", str(folder / "key.der"))
        openssl("x509", "-in", str(folder / "device.crt"), "-outform", "DER", "-out", str(folder / "cert.der"))
        http3["deviceCredentialId"] = "canary"
    (folder / "proxy.yml").write_text(json.dumps({
        "localPort": port, "httpProxyEnabled": False, "connectionsPerNode": 1, "systemProxy": {"enabled": False},
        "remoteServers": [{"host": "127.0.0.1", "port": 29443, "transport": "http3",
                           "cipher": cipher, "cipherKey": cipher_key, "http3": http3}],
        "route": {"defaultRoute": "proxy", "proxyList": [TARGET]}
    }))
    wrapping = base64.b64encode(secrets.token_bytes(32)).decode()
    launch = f"exec java -XX:ActiveProcessorCount=2 -Xmx64m -cp {work}/client.jar com.proxy.local.ProxyLocalServer proxy.yml"
    if device:
        launch = f"java -Xmx24m -cp {work} CanaryLease . && " + launch
    env = {"SIMPLEPLANE_LEASE_DIR": work + "/" + folder_label, "SIMPLEPLANE_LEASE_KEY": wrapping}
    with java_process(CLIENT, folder, [], env, script=launch, memory="155m") as (_, logs, _):
        try:
            for _ in range(80):
                try:
                    with socket.create_connection(("127.0.0.1", port), timeout=.3):
                        break
                except OSError:
                    time.sleep(.15)
            else:
                raise RuntimeError("canary client did not listen")
            socks(port, 1, ECHO_PORT)
            socks(port, 0xf0, ECHO_PORT)
            socks(port, 1, ECHO_PORT + 1)
            print("PASS TCP, framed UDP and closed target", folder_label, flush=True)
        except Exception:
            print(logs(), file=sys.stderr)
            raise


def inner():
    run("ip", "address", "add", TARGET + "/32", "dev", "lo")
    with subprocess.Popen([sys.executable, "-B", __file__, "echo"], stdout=subprocess.PIPE, text=True) as fixture:
        try:
            assert fixture.stdout.readline() == "READY\n"
            for label, account in zip(("first", "second"), ACCOUNTS):
                client(label, "aes-gcm-v2", (ROOT / "subscription.key").read_text(), account)
            client("legacy", "chacha20", (ROOT / "legacy.key").read_text(), None)
        finally:
            fixture.terminate()


def events(pattern):
    return [json.loads(line) for path in ROOT.glob("data/*/*/" + pattern) for line in path.read_text().splitlines()]


def outer():
    mem = re.search(r"MemAvailable:\s+(\d+)", Path("/proc/meminfo").read_text())
    if int(mem[1]) < 460 * 1024:
        raise RuntimeError("insufficient memory headroom for the isolated relay and client")
    certificate(ROOT, "device-ca", "canary devices")
    certificate(ROOT, "server-ca", "canary server")
    certificate(ROOT, "server", "localhost", ROOT / "server-ca", "extendedKeyUsage=serverAuth\nsubjectAltName=DNS:localhost\n")
    legacy = secrets.token_hex(16)
    (ROOT / "legacy.key").write_text(legacy)
    (ROOT / "subscription.key").write_text(secrets.token_hex(16))
    (ROOT / "remote.yml").write_text(
        "host: 127.0.0.1\nport: 29443\ntransport: http3\nworkerThreads: 1\nbizThreads: 2\n"
        f"cipher: chacha20\ncipherKey: '{legacy}'\nhttp3:\n  certificateFile: server.crt\n  privateKeyFile: server.key\n")
    work = str(ROOT) if NATIVE else "/work"
    config = (ROOT / "loglens.yaml").read_text()
    (ROOT / "loglens.yaml").write_text(config.replace("/var/lib/loglens", work + "/data").replace(
        "web:\n  enabled: true", "web:\n  enabled: false"))
    options = ["-XX:ActiveProcessorCount=2", "-Xmx70m", f"-javaagent:{work}/agent.jar=config={work}/loglens.yaml",
               "-Dsimpleplane.deviceCaFile=device-ca.crt", "-Dsimpleplane.subscriptionFrameKeyFile=subscription.key",
               "-cp", f"{work}:{work}/relay.jar", "com.proxy.remote.ProxyRemoteServer"]
    with java_process(RELAY, ROOT, options) as (pid, logs, stop):
        for _ in range(80):
            if "Http3Server started" in logs():
                break
            time.sleep(.15)
        else:
            raise RuntimeError("canary relay did not listen\n" + logs())
        if NATIVE:
            inner()
        else:
            subprocess.run(["nsenter", "-t", str(pid), "-n", sys.executable, "-B", __file__, "inner"], check=True, timeout=130)
        time.sleep(1)
        stop()
    telemetry = events("telemetry-*.jsonl")
    captured = events("captured-*.jsonl")
    for account in (*ACCOUNTS, None):
        owned = [row for row in telemetry if row.get("accountId") == account and row.get("targetHost") == TARGET]
        types = {row["kind"] for row in owned}
        assert {"SESSION_ACTIVE", "SESSION_CONNECT_FAILED", "SESSION_CLOSED"} <= types, (account, types)
        sessions = {row["proxySessionId"] for row in owned}
        assert any(row.get("mdc", {}).get("sp.proxySessionId") in sessions and
                   row.get("mdc", {}).get("sp.accountId") == account for row in captured)
    print("PASS distinct verified accounts on the same target, legacy attribution and correlated Logback")


if __name__ == "__main__":
    if ROOT.parent != Path("/var/tmp") or not re.fullmatch(r"sp-log-canary-[0-9a-f]{12}", ROOT.name):
        raise SystemExit("unexpected canary directory")
    if NATIVE and socket.if_nameindex() != [(1, "lo")]:
        raise SystemExit("native canary requires an isolated network namespace")
    if NATIVE:
        run("ip", "link", "set", "lo", "up")
    os.umask(0o077)
    {"outer": outer, "inner": inner, "echo": echo}[sys.argv[1] if len(sys.argv) > 1 else "outer"]()
