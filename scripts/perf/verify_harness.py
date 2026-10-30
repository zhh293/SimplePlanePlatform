"""Exercise measurement correctness without WAN or proxy vendor assumptions."""
import concurrent.futures
import http.server
import json
import select
import socket
import socketserver
import struct
import threading
import time
import unittest

import compare_proxies as bench
from remote_fixture import Origin


class Socks(socketserver.BaseRequestHandler):
    def handle(self):
        browser = self.request
        assert bench.read_n(browser, 3) == b'\x05\x01\x00'
        browser.sendall(b'\x05\x00')
        assert bench.read_n(browser, 4) == b'\x05\x01\x00\x03'
        bench.read_n(browser, bench.read_n(browser, 1)[0])
        port = struct.unpack('!H', bench.read_n(browser, 2))[0]
        with socket.create_connection(('127.0.0.1', port), timeout=3) as origin:
            browser.sendall(b'\x05\x00\x00\x01\x7f\x00\x00\x01\x00\x00')
            while True:
                readable, _, _ = select.select([browser, origin], [], [], 3)
                if not readable:
                    return
                for source in readable:
                    data = source.recv(65536)
                    if not data:
                        return
                    (origin if source is browser else browser).sendall(data)


class Threads(socketserver.ThreadingTCPServer):
    daemon_threads = True
    allow_reuse_address = True


class Corrupt(Origin):
    def do_GET(self):
        self.send_response(200); self.send_header('Content-Length', '1024'); self.end_headers()
        self.wfile.write(b'X'*1024)


class Truncated(Origin):
    def do_GET(self):
        self.send_response(200); self.send_header('Content-Length', '1024'); self.end_headers()
        self.wfile.write(b'x'); self.wfile.flush(); self.close_connection = True


class Slow(Origin):
    def do_GET(self):
        self.close_connection = True
        self.send_response(200); self.send_header('Content-Length', '1024'); self.end_headers()
        time.sleep(.5)
        try:
            self.wfile.write(b'X'*1024)
        except (BrokenPipeError, ConnectionResetError):
            pass


class HarnessTest(unittest.TestCase):
    def setUp(self):
        self.servers = []
        self.socks = self.server(Threads, Socks)
        self.origin = self.server(http.server.ThreadingHTTPServer, Origin)

    def server(self, cls, handler):
        server = cls(('127.0.0.1', 0), handler)
        server.daemon_threads = True
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.servers.append(server)
        return server.server_address[1]

    def tearDown(self):
        for server in self.servers:
            server.shutdown(); server.server_close()

    def test_exact_download_digest(self):
        result = bench.fetch(self.socks, size=131073, target_port=self.origin)
        self.assertTrue(result['ok']); self.assertEqual(result['bytes'], 131073)
        self.assertGreater(result['total_ms'], result['setup_ms'])

    def test_upload_validates_remote_digest(self):
        result = bench.fetch(self.socks, size=131073, target_port=self.origin, upload=True)
        self.assertTrue(result['ok']); self.assertEqual(result['payload_bytes'], 131073)

    def test_keepalive_multiple_responses(self):
        with bench.tunnel(self.socks, target_port=self.origin) as sock:
            for size in [1, 1024, 65537]:
                result = bench.fetch(self.socks, size=size, sock=sock)
                self.assertEqual(result['bytes'], size)

    def test_corruption_is_failure(self):
        origin = self.server(http.server.ThreadingHTTPServer, Corrupt)
        with self.assertRaisesRegex(AssertionError, 'corruption'):
            bench.fetch(self.socks, target_port=origin)

    def test_truncation_is_failure(self):
        origin = self.server(http.server.ThreadingHTTPServer, Truncated)
        with self.assertRaises((AssertionError, http.client.IncompleteRead)):
            bench.fetch(self.socks, target_port=origin)

    def test_request_has_overall_timeout(self):
        origin = self.server(http.server.ThreadingHTTPServer, Slow)
        started = time.monotonic()
        with self.assertRaises(TimeoutError):
            bench.fetch(self.socks, target_port=origin, timeout=.15)
        self.assertLess(time.monotonic()-started, .4)

    def test_parallel_does_not_hide_failures(self):
        real_fetch = bench.fetch
        calls = iter([True, False, True, False])
        def fake(*args, **kwargs):
            if next(calls):
                return {'ok': True, 'payload_bytes': 1024}
            raise OSError('planned failure')
        bench.fetch = fake
        try:
            result = bench.parallel(self.socks, 1, 1024, 4)
            self.assertFalse(result['ok']); self.assertEqual(result['successes'], 2)
            self.assertEqual(result['count'], 4)
            self.assertLess(result['rps'], result['attempted_rps'])
        finally:
            bench.fetch = real_fetch


if __name__ == '__main__':
    unittest.main(verbosity=2)
