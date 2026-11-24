"""Check traffic against the echo process in the canary node's private network."""
import base64
import json
import os
from pathlib import Path
import secrets
import socket
import struct
import subprocess
import tempfile
import time

from cryptography import x509
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.ciphers.aead import AESGCM


def read_exact(sock, count):
    result = bytearray()
    while len(result) < count:
        part = sock.recv(count - len(result))
        if not part:
            raise RuntimeError("SOCKS connection closed before reply")
        result.extend(part)
    return bytes(result)


def socks(sock, command, port):
    sock.sendall(bytes.fromhex("050100"))
    assert read_exact(sock, 2) == bytes.fromhex("0500")
    addr = socket.inet_aton("192.0.2.73")
    sock.sendall(bytes([5, command, 0, 1]) + addr + struct.pack("!H", port))
    header = read_exact(sock, 4)
    if header != bytes([5, 0, 0, 1]):
        raise RuntimeError("SOCKS command failed: " + header.hex())
    return socket.inet_ntoa(read_exact(sock, 4)), struct.unpack("!H", read_exact(sock, 2))[0]


def roundtrips(port):
    echo = 28371
    with socket.create_connection(("127.0.0.1", port), 12) as tcp:
        socks(tcp, 1, echo)
        mark = secrets.token_bytes(32)
        tcp.sendall(mark)
        assert read_exact(tcp, 40) == b"sp-gray:" + mark
    # The desktop TUN sends framed datagrams on a loopback TCP connection.
    with socket.create_connection(("127.0.0.1", port), 12, source_address=("127.0.0.2", 0)) as ctl:
        socks(ctl, 0xf0, echo)
        mark = secrets.token_bytes(32)
        ctl.sendall(struct.pack("!H", len(mark)) + mark)
        assert read_exact(ctl, 2) == struct.pack("!H", 40)
        assert read_exact(ctl, 40) == b"sp-gray:" + mark


def exercise_relay(work, node, tls_key, jar, pod=None):
    java = os.environ.get("SUBSCRIPTION_TEST_JAVA", "java")
    with tempfile.TemporaryDirectory(dir=work) as name:
        directory = Path(name)
        ca = directory / "root.pem"
        ca.write_text(node["caPem"])
        tls = tls_key.private_bytes(serialization.Encoding.DER, serialization.PrivateFormat.PKCS8,
                                    serialization.NoEncryption())
        cert = x509.load_pem_x509_certificate(node["deviceCertificatePem"].encode())
        clear = struct.pack("!H", len(tls)) + tls + cert.public_bytes(serialization.Encoding.DER)
        wrap, nonce = secrets.token_bytes(32), secrets.token_bytes(12)
        blob = nonce + AESGCM(wrap).encrypt(nonce, clear, node["id"].encode())
        (directory / "leases.properties").write_text(node["id"] + "=" + base64.b64encode(blob).decode())
        with socket.socket() as reserve:
            reserve.bind(("127.0.0.1", 0))
            port = reserve.getsockname()[1]
        config = dict(localPort=port, httpProxyEnabled=False, connectionsPerNode=1, systemProxy={"enabled": False},
                      remoteServers=[dict(host=node["host"], port=node["port"], transport="http3",
                      cipher=node["cipher"], cipherKey=node["key"], http3=dict(serverName=node["serverName"],
                      caFile=str(ca), deviceCredentialId=node["id"]))],
                      route=dict(defaultRoute="proxy", proxyList=["192.0.2.73"]))
        cfg = directory / "proxy.yml"
        cfg.write_text(json.dumps(config))
        env = dict(os.environ, SIMPLEPLANE_LEASE_DIR=str(directory), SIMPLEPLANE_LEASE_KEY=base64.b64encode(wrap).decode())
        launcher = [java]
        if pod:
            launcher = ["docker", "run", "--rm", "--name", work.name + "-client", "--label", "simpleplane.canary=" + work.name,
                        "--network", "container:" + pod, "--memory", "150m", "--memory-swap", "150m", "--cpus", "1",
                        "--read-only", "--cap-drop", "ALL", "--security-opt", "no-new-privileges", "--pids-limit", "96",
                        "--tmpfs", "/tmp:rw,exec,nosuid,size=32m", "-w", "/tmp", "-v", f"{directory}:{directory}:rw",
                        "-v", f"{jar}:{jar}:ro", "-e", "SIMPLEPLANE_LEASE_DIR", "-e", "SIMPLEPLANE_LEASE_KEY",
                        "--entrypoint", "java", "eclipse-temurin:17-jre-jammy"]
        with (directory / "proxy.log").open("wb") as output:
            process = subprocess.Popen(launcher + ["-XX:ActiveProcessorCount=2", "-Djava.net.preferIPv4Stack=true", "-Xmx64m", "-cp", str(jar.resolve()),
                        "com.proxy.local.ProxyLocalServer", str(cfg)], cwd=name, stdout=output, stderr=subprocess.STDOUT, env=env)
        try:
            deadline = time.monotonic() + 20
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError(f"Java local proxy exited during startup ({process.returncode})")
                try:
                    with socket.create_connection(("127.0.0.1", port), .3):
                        break
                except OSError:
                    time.sleep(.15)
            roundtrips(port)
            log = (directory / "proxy.log").read_text(errors="replace")
            assert "Route PROXY (SOCKS5): 192.0.2.73:28371" in log
            assert "HTTP/3 QUIC handshake established" in log
        except Exception:
            import re
            log = (directory / "proxy.log").read_text(errors="replace")
            types = re.findall(r"(?:[A-Za-z.]+\.)?([A-Za-z]+(?:Exception|Error))(?=:)", log)
            print("Java diagnostics:", ", ".join(dict.fromkeys(types)) or "no exception class", flush=True)
            if pod:
                (work / "client-last.log").write_text(log)
            raise
        finally:
            if pod:
                subprocess.run(["docker", "rm", "-f", work.name + "-client"], capture_output=True)
            process.terminate()
            process.wait(10)


async def observe_expiry(node, key):
    import asyncio
    from aioquic.asyncio import connect, QuicConnectionProtocol
    from aioquic.h3.connection import H3Connection
    from aioquic.h3.events import HeadersReceived
    from aioquic.quic.configuration import QuicConfiguration
    from aioquic.quic.events import ConnectionTerminated

    class Probe(QuicConnectionProtocol):
        def __init__(self, *args, **kwargs):
            super().__init__(*args, **kwargs)
            self.h3 = H3Connection(self._quic)
            self.events = asyncio.Queue()

        def quic_event_received(self, event):
            if isinstance(event, ConnectionTerminated):
                self.events.put_nowait(("closed", time.time()))
            for frame in self.h3.handle_event(event):
                if isinstance(frame, HeadersReceived):
                    self.events.put_nowait(("http", dict(frame.headers).get(b":status")))

    cfg = QuicConfiguration(is_client=True, alpn_protocols=["h3"], server_name=node["serverName"], idle_timeout=30)
    cfg.load_verify_locations(cadata=node["caPem"].encode())
    cfg.certificate = x509.load_pem_x509_certificate(node["deviceCertificatePem"].encode())
    cfg.private_key = key
    expiry = cfg.certificate.not_valid_after_utc.timestamp()
    async with connect(node["host"], node["port"], configuration=cfg, create_protocol=Probe) as conn:
        conn.h3.send_headers(conn._quic.get_next_available_stream_id(), [
            (b":method", b"POST"), (b":scheme", b"https"), (b":authority", node["host"].encode()),
            (b":path", b"/proxy"), (b"x-plane-protocol", b"proxy-message-v1")])
        conn.transmit()
        assert await asyncio.wait_for(conn.events.get(), 5) == ("http", b"200")
        print("ACTIVE", flush=True)
        while time.time() < expiry + 3:
            try:
                event, ended = await asyncio.wait_for(conn.events.get(), 1)
            except asyncio.TimeoutError:
                if time.time() < expiry - 1:
                    await asyncio.wait_for(conn.ping(), 2)
                continue
            assert event == "closed" and -1 <= ended - expiry <= 2
            print(f"PASS active QUIC closed at expiry; lag={ended-expiry:.2f}s", flush=True)
            return
        raise RuntimeError("Active connection did not close at lease expiry")


if __name__ == "__main__":
    # Invoked over SSH inside only this run's pod network. Keep the TLS private
    # key on this host and exchange its public key for an actual signed manifest.
    import sys
    import re
    from cryptography.hazmat.primitives.asymmetric import ec
    work = Path(__file__).resolve().parent
    assert work.parent == Path("/var/tmp") and re.fullmatch(r"sp-sub-[0-9a-f]{12}", work.name)
    mem = re.search(r"MemAvailable:\s+(\d+)", Path("/proc/meminfo").read_text())
    if int(mem.group(1)) < 460 * 1024:
        raise SystemExit("Insufficient memory headroom")
    key = ec.generate_private_key(ec.SECP256R1())
    spki = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    print(base64.b64encode(spki).decode(), flush=True)
    node = json.loads(sys.stdin.readline(65536))
    if sys.argv[1:] == ["observe-expiry"]:
        import asyncio
        asyncio.run(observe_expiry(node, key))
    else:
        exercise_relay(work, node, key, work / "client.jar", work.name + "-pod")
        print("PASS namespace TCP and UDP echo", flush=True)
