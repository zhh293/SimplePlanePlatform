from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import time


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/slow":
            time.sleep(3)
            body = b"slow"
        else:
            body = b"fast"
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        with open("/home/ec2-user/h3-hol-target.log", "a", encoding="utf-8") as log:
            log.write("%s - %s\n" % (self.address_string(), format % args))


ThreadingHTTPServer(("127.0.0.1", 18081), Handler).serve_forever()
