"""Local mirror of the production nginx mapping, for developing the linked-match mode.

    python tools/dev-server.py [port]        # default 8080, bound to all interfaces

In production the tournament's web console is served from https://<host>/15ball/ and the API from
https://<host>/15ball/api/, which nginx proxies to the Go server's /api/. This does the same thing
locally so that a scoring link works unchanged:

  * /15ball/api/...   -> proxied to the Go server on 127.0.0.1:8093
  * /15ball/...       -> served from the reference clone (its static web console)
  * anything else     -> 404

Binding to all interfaces is deliberate: the emulator reaches the host as 10.0.2.2 and a phone on the
same network as the host's LAN address, so both can open the same link.

Set SCORE_REF to point at a different clone. The Go server is expected to be running already
(see docs/LINKED-MATCH-DESIGN.md).
"""
import os
import sys
import urllib.error
import urllib.request
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REF = os.environ.get("SCORE_REF", r"C:/Users/kevin/score-ref/15ball-scoresheet")
API_UPSTREAM = os.environ.get("API_UPSTREAM", "http://127.0.0.1:8093")
PREFIX = "/15ball"
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8080

HOP_BY_HOP = {"connection", "keep-alive", "transfer-encoding", "te", "trailer", "upgrade",
              "proxy-authorization", "proxy-authenticate", "host", "content-length"}


class Handler(SimpleHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def translate_path(self, path):
        # everything under /15ball/ maps onto the clone's root
        rel = path[len(PREFIX):].lstrip("/") or "index.html"
        return os.path.join(REF, *rel.split("/"))

    def log_message(self, fmt, *args):
        sys.stdout.write("%s - %s\n" % (self.address_string(), fmt % args))
        sys.stdout.flush()

    def _proxy(self):
        target = API_UPSTREAM + self.path[len(PREFIX):]
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else None
        headers = {k: v for k, v in self.headers.items() if k.lower() not in HOP_BY_HOP}
        request = urllib.request.Request(target, data=body, headers=headers, method=self.command)
        try:
            with urllib.request.urlopen(request, timeout=30) as upstream:
                payload = upstream.read()
                self.send_response(upstream.status)
                for key, value in upstream.headers.items():
                    if key.lower() not in HOP_BY_HOP:
                        self.send_header(key, value)
        except urllib.error.HTTPError as err:
            payload = err.read()
            self.send_response(err.code)
            for key, value in err.headers.items():
                if key.lower() not in HOP_BY_HOP:
                    self.send_header(key, value)
        except Exception as err:  # upstream down or unreachable
            payload = ('{"error":{"code":"proxy_failed","message":"%s"}}' % err).encode()
            self.send_response(502)
            self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def _dispatch(self):
        if self.path.startswith(PREFIX + "/api/"):
            self._proxy()
        elif self.path.startswith(PREFIX + "/"):
            SimpleHTTPRequestHandler.do_GET(self)
        else:
            self.send_error(404, "not under " + PREFIX)

    def do_GET(self):
        self._dispatch()

    def do_POST(self):
        self._dispatch()

    def do_PATCH(self):
        self._dispatch()


def main():
    if not os.path.isdir(REF):
        raise SystemExit("reference clone not found: " + REF)
    print("serving %s at http://0.0.0.0:%d%s/  (api -> %s)" % (REF, PORT, PREFIX, API_UPSTREAM))
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
