"""本地瓦片服务器：记录 MapLibre 在某个地图缩放下实际请求的 z。

用途：确认 tileSize=256 时 MapLibre 是否会自动请求 z+1 的瓦片
（这决定了地图上"最清晰能放大到第几级"要比瓦片层面低一级）。
"""
import http.server, socketserver, sys

LOG = open(sys.argv[1], "a", encoding="utf-8", buffering=1)

class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        LOG.write(f"REQ {self.path}\n")
        try:
            with open(sys.argv[2], "rb") as f:
                body = f.read()
        except Exception:
            body = b""
        self.send_response(200)
        self.send_header("Content-Type", "image/jpeg")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
    def log_message(self, *a):
        pass

port = int(sys.argv[3])
socketserver.TCPServer.allow_reuse_address = True
with socketserver.TCPServer(("127.0.0.1", port), H) as httpd:
    httpd.serve_forever()
