"""Incrementally ship LogLens metadata and redacted application logs to Elasticsearch."""

import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import sqlite3
import ssl
import sys
import time
from urllib.request import HTTPRedirectHandler, HTTPSHandler, Request, build_opener
from urllib.parse import urlsplit

KEYWORDS = ("event_id", "dataset", "node_id", "account_id", "device_id", "proxy_session_id",
            "connection_id", "stream_id", "event_kind", "transport", "target_host",
            "domain_suffix", "level", "logger", "exception_type", "trace_id", "template", "reason")
PROPERTIES = {
    **{key: {"type": "keyword", "ignore_above": 2048} for key in KEYWORDS},
    "@timestamp": {"type": "date"},
    "target_ip": {"type": "ip"},
    "target_port": {"type": "integer"},
    "uplink_bytes": {"type": "long"},
    "downlink_bytes": {"type": "long"},
    "message": {"type": "text"},
}
NODE = re.compile(r"[a-z0-9][a-z0-9_-]{0,95}")
ACCOUNT = re.compile(r"[0-9a-f]{64}")
DEVICE = re.compile(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
FILE = re.compile(r"(captured|telemetry)-\d+\.jsonl")
DOMAIN = re.compile(r"[a-z0-9_](?:[a-z0-9_-]{0,61}[a-z0-9_])?")
SESSION_EVENTS = {"SESSION_OPENED", "SESSION_ACTIVE", "SESSION_CONNECT_FAILED", "SESSION_CLOSED"}
MAX_LINE = 16 * 1024 * 1024 + 2
BATCH_BYTES = 512 * 1024


def string(value, maximum=2048):
    return value[:maximum] if isinstance(value, str) and value else None


def identified(value, pattern):
    return value if isinstance(value, str) and pattern.fullmatch(value) else None


def target_fields(host):
    if not isinstance(host, str) or len(host) > 255:
        return {}
    host = host.lower().rstrip(".")
    try:
        address = ipaddress.ip_address(host.removeprefix("[").removesuffix("]"))
        return {"target_host": str(address), "target_ip": str(address)}
    except ValueError:
        pass
    try:
        host = host.encode("idna").decode("ascii")
    except UnicodeError:
        return {}
    labels = host.split(".")
    if any(not DOMAIN.fullmatch(label) for label in labels):
        return {}
    suffixes = [".".join(labels[i:]) for i in range(max(1, len(labels) - 1))]
    return {"target_host": host, "domain_suffix": suffixes}


def document(row, source, node, cutoff, now):
    stamp = row.get("eventTime")
    event_id = string(row.get("eventId"), 256)
    if not isinstance(stamp, int) or isinstance(stamp, bool) or not event_id:
        raise ValueError("event is missing its timestamp or ID")
    if stamp < cutoff or stamp > now + 300_000:
        return None
    fields = {"@timestamp": stamp, "event_id": event_id, "node_id": node}
    if source == "telemetry":
        if row.get("kind") not in SESSION_EVENTS:
            return None
        fields.update(dataset="connection", event_kind=row["kind"],
                      account_id=identified(row.get("accountId"), ACCOUNT), device_id=identified(row.get("deviceId"), DEVICE),
                      proxy_session_id=string(row.get("proxySessionId")), connection_id=string(row.get("connectionId")),
                      stream_id=string(row.get("streamId")), transport=string(row.get("transport"), 16),
                      reason=string(row.get("closeReason"), 128))
        fields.update(target_fields(row.get("targetHost")))
        for key, name in (("targetPort", "target_port"), ("uplinkBytes", "uplink_bytes"), ("downlinkBytes", "downlink_bytes")):
            value = row.get(key)
            if type(value) is int and 0 <= value <= (65535 if key == "targetPort" else 2**63 - 1):
                fields[name] = value
    else:
        mdc = row.get("mdc") or {}
        if not isinstance(mdc, dict):
            mdc = {}
        exception = row.get("exception")
        fields.update(dataset="application", account_id=identified(mdc.get("sp.accountId"), ACCOUNT),
                      device_id=identified(mdc.get("sp.deviceId"), DEVICE), proxy_session_id=string(mdc.get("sp.proxySessionId")),
                      trace_id=string(mdc.get("traceId"), 128), level=string(row.get("level"), 16),
                      logger=string(row.get("logger"), 512), template=string(row.get("template"), 1024),
                      message=string(row.get("message"), 8192),
                      exception_type=string(exception.get("type"), 512) if isinstance(exception, dict) else None)
    return {key: value for key, value in fields.items() if value is not None}


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Elasticsearch:
    def __init__(self, base, prefix, canary=False, public_http=False):
        endpoint = urlsplit(base)
        paths = ("", "/") if canary or public_http else ("", "/", "/_simpleplane/log-ingest", "/_simpleplane/log-ingest/")
        if not endpoint.hostname or endpoint.username or endpoint.password or endpoint.query or endpoint.fragment or endpoint.path not in paths:
            raise ValueError("invalid Elasticsearch origin")
        if not re.fullmatch(r"sp-log-[a-z0-9][a-z0-9-]{0,39}", prefix):
            raise ValueError("invalid log index prefix")
        self.base = base.rstrip("/")
        self.prefix = prefix
        self.authorization = os.environ.get("SP_LOG_ES_AUTH", "")
        if canary and not prefix.startswith("sp-log-canary-"):
            raise ValueError("insecure access requires a canary index prefix")
        if public_http and (canary or prefix != "sp-log-prod" or endpoint.port != 9200
                            or endpoint.scheme != "http" or self.authorization):
            raise ValueError("public HTTP mode requires the unauthenticated production port and index prefix")
        if endpoint.scheme not in ({"http", "https"} if (canary or public_http) and not self.authorization else {"https"}):
            raise ValueError("an HTTPS log index is required")
        if not canary and not public_http and not self.authorization:
            raise ValueError("Elasticsearch authorization is required")
        tls = ssl.create_default_context(cafile=os.environ.get("SP_LOG_ES_CA"))
        self.opener = build_opener(NoRedirect(), HTTPSHandler(context=tls))

    def request(self, method, path, body, content_type="application/json"):
        headers = {"Content-Type": content_type}
        if self.authorization:
            headers["Authorization"] = self.authorization
        request = Request(self.base + path, data=body, method=method, headers=headers)
        with self.opener.open(request, timeout=8) as result:
            return json.load(result)

    def prepare(self):
        body = {"index_patterns": [self.prefix + "-*"],
                "template": {"settings": {"number_of_shards": 1, "number_of_replicas": 0},
                             "mappings": {"dynamic": "strict", "properties": PROPERTIES}}}
        self.request("PUT", "/_index_template/" + self.prefix, json.dumps(body).encode())

    def lines(self, row):
        stamp = datetime.fromtimestamp(row["@timestamp"] / 1000, timezone.utc)
        index = self.prefix + "-" + stamp.strftime("%Y.%m.%d")
        identity = "\0".join([row["node_id"], row["dataset"], row["event_id"]])
        key = hashlib.sha256(identity.encode()).hexdigest()
        action = {"create": {"_index": index, "_id": key}}
        return (json.dumps(action) + "\n" + json.dumps(row, ensure_ascii=False) + "\n").encode()

    def bulk(self, rows):
        if not rows:
            return
        response = self.request("POST", "/_bulk", b"".join(rows), "application/x-ndjson")
        items = response.get("items", [])
        if len(items) != len(rows):
            raise RuntimeError("invalid Elasticsearch bulk response")
        for item in items:
            result = item["create"]
            status = result["status"]
            error = result.get("error", {}).get("type", "")
            if status != 201 and (status != 409 or error != "version_conflict_engine_exception"):
                raise RuntimeError(f"Elasticsearch rejected a log event ({status}, {error})")


class Ship:
    def __init__(self, root, node, state, retention, elastic):
        self.root = root
        self.node = node
        self.retention = retention
        self.elastic = elastic
        self.db = sqlite3.connect(state)
        self.db.execute("CREATE TABLE IF NOT EXISTS checkpoint (path TEXT PRIMARY KEY, offset INTEGER NOT NULL)")

    def scan(self):
        now = datetime.now(timezone.utc)
        cutoff = int((now - timedelta(days=self.retention)).timestamp() * 1000)
        now_ms = int(now.timestamp() * 1000)
        count = 0
        for path in sorted(self.root.glob("*/*/*.jsonl")):
            match = FILE.fullmatch(path.name)
            if not match or path.is_symlink():
                continue
            key = str(path.relative_to(self.root))
            point = self.db.execute("SELECT offset FROM checkpoint WHERE path=?", (key,)).fetchone()
            start = point[0] if point else 0
            rows = []
            size = 0
            with path.open("rb") as file:
                if os.fstat(file.fileno()).st_size < start:
                    raise RuntimeError("LogLens segment shrank after its checkpoint")
                file.seek(start)
                for _ in range(250):
                    before = file.tell()
                    line = file.readline(MAX_LINE)
                    if len(line) >= MAX_LINE:
                        raise RuntimeError("LogLens segment contains an oversized row")
                    if not line.endswith(b"\n"):
                        file.seek(before)
                        break
                    row = document(json.loads(line), match[1], self.node, cutoff, now_ms)
                    if row:
                        encoded = self.elastic.lines(row)
                        if rows and size + len(encoded) > BATCH_BYTES:
                            file.seek(before)
                            break
                        rows.append(encoded)
                        size += len(encoded)
                end = file.tell()
            self.elastic.bulk(rows)
            if end != start:
                with self.db:
                    self.db.execute("INSERT OR REPLACE INTO checkpoint(path,offset) VALUES(?,?)", (key, end))
            count += len(rows)
        return count


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["prepare", "ship"])
    parser.add_argument("--url", required=True)
    parser.add_argument("--prefix", required=True)
    parser.add_argument("--node")
    parser.add_argument("--root", type=Path)
    parser.add_argument("--state", type=Path)
    parser.add_argument("--retention-days", type=int, choices=range(1, 91), default=14)
    parser.add_argument("--allow-insecure-canary", action="store_true")
    parser.add_argument("--allow-public-http", action="store_true")
    parser.add_argument("--once", action="store_true")
    options = parser.parse_args()
    elastic = Elasticsearch(options.url, options.prefix, options.allow_insecure_canary, options.allow_public_http)
    if options.action == "prepare":
        elastic.prepare()
        return
    if not options.root or not options.state or not identified(options.node, NODE) or not options.root.is_dir():
        parser.error("ship requires an existing root directory, checkpoint file path and valid node ID")
    ship = Ship(options.root.resolve(), options.node, options.state, options.retention_days, elastic)
    delay = 2
    while True:
        try:
            indexed = ship.scan()
            if indexed:
                print(f"submitted {indexed} log documents", flush=True)
            delay = 2
            if options.once:
                return
        except (OSError, ValueError, RuntimeError, sqlite3.Error) as error:
            print(f"log shipping paused ({type(error).__name__})", file=sys.stderr, flush=True)
            if options.once:
                raise SystemExit(1)
            time.sleep(delay)
            delay = min(60, delay * 2)
            continue
        time.sleep(2)


if __name__ == "__main__":
    main()
