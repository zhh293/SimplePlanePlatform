"""Verify the protected proxy entry point using a separate QUIC implementation.

Requires aioquic and cryptography. The fixture binds only loopback and removes
its short-lived test keys after stopping the Java process.
"""

import argparse
import asyncio
import contextlib
import datetime as dt
import json
import os
import secrets
from pathlib import Path
import socket
import subprocess
import tempfile
import uuid

from aioquic.asyncio import QuicConnectionProtocol, connect
from aioquic.h3.connection import H3Connection
from aioquic.h3.events import HeadersReceived
from aioquic.quic.configuration import QuicConfiguration
from aioquic.quic.events import ConnectionTerminated
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID


def identity(directory, name, issuer=None, purpose=None, lifetime=600):
    key = ec.generate_private_key(ec.SECP256R1())
    subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, name)])
    now = dt.datetime.now(dt.timezone.utc)
    cert = (x509.CertificateBuilder().subject_name(subject)
            .issuer_name(issuer[1].subject if issuer else subject)
            .public_key(key.public_key()).serial_number(x509.random_serial_number())
            .not_valid_before(now - dt.timedelta(seconds=60))
            .not_valid_after(now + dt.timedelta(seconds=lifetime))
            .add_extension(x509.BasicConstraints(ca=issuer is None, path_length=None), True))
    if purpose is not None:
        cert = cert.add_extension(x509.ExtendedKeyUsage([purpose]), False)
    if purpose == ExtendedKeyUsageOID.SERVER_AUTH:
        cert = cert.add_extension(x509.SubjectAlternativeName([x509.DNSName("localhost")]), False)
    cert = cert.sign(issuer[0] if issuer else key, hashes.SHA256())
    (directory / (name + ".crt")).write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    (directory / (name + ".key")).write_bytes(key.private_bytes(
        serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    return key, cert


class Probe(QuicConnectionProtocol):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.h3 = H3Connection(self._quic)
        self.responses = asyncio.Queue()

    def quic_event_received(self, event):
        if isinstance(event, ConnectionTerminated):
            self.responses.put_nowait(("closed", event.error_code))
        for frame in self.h3.handle_event(event):
            if isinstance(frame, HeadersReceived):
                self.responses.put_nowait(("http", dict(frame.headers).get(b":status")))

    def request(self):
        self.h3.send_headers(self._quic.get_next_available_stream_id(), [
            (b":method", b"POST"), (b":scheme", b"https"), (b":authority", b"localhost"),
            (b":path", b"/proxy"), (b"x-plane-protocol", b"proxy-message-v1")])
        self.transmit()


async def verify(port, directory, credential, permitted, expiry=False):
    cfg = QuicConfiguration(is_client=True, alpn_protocols=["h3"], server_name="localhost",
                            cafile=str(directory / "server-ca.crt"), idle_timeout=45)
    if credential:
        cfg.load_cert_chain(directory / (credential + ".crt"), directory / (credential + ".key"))
    try:
        async with connect("127.0.0.1", port, configuration=cfg, create_protocol=Probe) as probe:
            probe.request()
            event = await asyncio.wait_for(probe.responses.get(), 5)
            if permitted:
                assert event == ("http", b"200"), event
                if expiry:
                    deadline = x509.load_pem_x509_certificate(
                        (directory / (credential + ".crt")).read_bytes()).not_valid_after_utc
                    event = await asyncio.wait_for(probe.responses.get(), 20)
                    lag = (dt.datetime.now(dt.timezone.utc) - deadline).total_seconds()
                    assert event[0] == "closed" and -1 <= lag <= 2, (event, lag)
            else:
                assert event[0] == "closed" and event[1] != 0, event
    except ConnectionError:
        if permitted:
            raise


async def exercise(args, directory):
    server_ca = identity(directory, "server-ca")
    trusted_ca = identity(directory, "device-ca")
    foreign_ca = identity(directory, "other-node-ca")
    identity(directory, "server", server_ca, ExtendedKeyUsageOID.SERVER_AUTH)
    identity(directory, "valid", trusted_ca, ExtendedKeyUsageOID.CLIENT_AUTH)
    identity(directory, "foreign", foreign_ca, ExtendedKeyUsageOID.CLIENT_AUTH)
    identity(directory, "expired", trusted_ca, ExtendedKeyUsageOID.CLIENT_AUTH, -5)
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    cipher = "cipher: none"
    extra = []
    if args.shared:
        cipher = "cipher: chacha20\ncipherKey: '" + secrets.token_hex(16) + "'"
        (directory / "subscription.key").write_text(secrets.token_hex(16))
        extra = ["-Dsimpleplane.subscriptionFrameKeyFile=subscription.key"]
    (directory / "remote.yml").write_text(
        f"host: 127.0.0.1\nport: {port}\ntransport: http3\nbizThreads: 2\nworkerThreads: 1\n"
        f"{cipher}\nhttp3:\n  certificateFile: server.crt\n  privateKeyFile: server.key\n",
        encoding="utf-8")
    container = "sp-subscription-mtls-" + uuid.uuid4().hex[:12]
    command = [args.java, "-XX:ActiveProcessorCount=2", "-Xmx128m",
               "-Dsimpleplane.deviceCaFile=device-ca.crt", *extra, "-cp",
               os.pathsep.join([str(directory), str(args.jar.resolve())]),
               "com.proxy.remote.ProxyRemoteServer"]
    if args.java_image:
        jar = args.jar.resolve()
        command = ["docker", "run", "--rm", "--name", container, "--network", "host",
                   "--read-only", "--tmpfs", "/tmp:rw,exec,nosuid,size=64m",
                   "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                   "--memory", "256m", "--memory-swap", "256m", "--pids-limit", "96",
                   "--cpus", "1", "-v", f"{directory}:{directory}:ro", "-v", f"{jar}:{jar}:ro",
                   "-w", str(directory), "--entrypoint", args.java, args.java_image] + command[1:]
    log = directory / "service.log"
    with log.open("wb") as output, subprocess.Popen(command, cwd=directory, stdout=output,
                                                     stderr=subprocess.STDOUT) as process:
        try:
            for _ in range(160):
                if process.poll() is not None:
                    raise RuntimeError(log.read_text(errors="replace")[-2000:])
                if "Http3Server started" in log.read_text(errors="replace"):
                    break
                await asyncio.sleep(0.1)
            else:
                raise RuntimeError("protected service did not become ready")
            for credential in ["valid", None, "foreign", "expired"]:
                permitted = credential == "valid" or (args.shared and credential is None)
                await asyncio.wait_for(verify(port, directory, credential, permitted), 9)
                print(json.dumps({"credential": credential, "expected":
                      "accepted" if permitted else "rejected", "pass": True}), flush=True)
            identity(directory, "short", trusted_ca, ExtendedKeyUsageOID.CLIENT_AUTH, 8)
            await verify(port, directory, "short", True, expiry=True)
            print('{"active_connection_closed_at_expiry":true}', flush=True)
            await verify(port, directory, "valid", True)
        finally:
            if args.java_image:
                subprocess.run(["docker", "rm", "-f", container], check=False,
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=20)
            if process.poll() is None:
                process.terminate()
                with contextlib.suppress(subprocess.TimeoutExpired):
                    process.wait(5)
                if process.poll() is None:
                    process.kill()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--java", default="java")
    parser.add_argument("--java-image", help="Run Java in this preinstalled, memory-limited Docker image")
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--shared", action="store_true", help="Test the shared legacy/device listener")
    with tempfile.TemporaryDirectory(prefix="subscription-mtls-") as temp:
        asyncio.run(exercise(parser.parse_args(), Path(temp).resolve()))
