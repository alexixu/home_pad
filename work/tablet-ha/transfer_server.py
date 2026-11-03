from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from pathlib import Path
import json, secrets, shutil
ROOT=Path(__file__).resolve().parent
STAGE=ROOT/'staging'
KEY=ROOT/'transfer-key'
if not KEY.exists():
    KEY.write_text(secrets.token_urlsafe(32))
    KEY.chmod(0o600)
TOKEN=KEY.read_text().strip()
ALLOWED={'prepare.sh','ha-rootfs.tar.gz','ha-config.tar.gz','start-tablet-ha','run-in-ha','finish.sh','restart.sh'}
class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args): pass
    def do_GET(self):
        prefix='/'+TOKEN+'/'
        name=self.path[len(prefix):] if self.path.startswith(prefix) else ''
        path=STAGE/name
        if name not in ALLOWED or not path.is_file():
            self.send_error(404);return
        self.send_response(200)
        self.send_header('Content-Length',str(path.stat().st_size))
        self.send_header('Content-Type','application/octet-stream')
        self.end_headers()
        try:
            with path.open('rb') as f: shutil.copyfileobj(f,self.wfile)
        except (BrokenPipeError, ConnectionResetError): pass
    def do_POST(self):
        if self.path!='/'+TOKEN+'/status':
            self.send_error(404);return
        length=min(int(self.headers.get('Content-Length','0')),8192)
        data=self.rfile.read(length).decode('utf-8','replace')
        with (ROOT/'transfer-events.log').open('a') as f:f.write(data+'\n')
        self.send_response(204);self.end_headers()
if __name__=='__main__':
    ThreadingHTTPServer(('127.0.0.1',18080),Handler).serve_forever()
