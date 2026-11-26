"""Local acceptance recorder. No public ingress, outbound delivery, secrets or raw alert bodies."""
from collections import deque
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import json, os, re, threading

RECEIPTS = deque(maxlen=5000)
LOCK = threading.Lock()
FIXTURES = os.environ.get("OMP_ACCEPTANCE_FIXTURES") == "true"
METRICS = ""


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass

    def reply(self, code, value, content_type="application/json"):
        data = value.encode() if isinstance(value, str) else json.dumps(value).encode()
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/health":
            self.reply(200, {"status": "UP"})
        elif FIXTURES and self.path == "/receipts":
            with LOCK:
                records = list(RECEIPTS)
            self.reply(200, records)
        elif FIXTURES and self.path == "/metrics":
            with LOCK:
                value = METRICS
            self.reply(200, value, "text/plain; version=0.0.4")
        else:
            self.reply(404, {})

    def do_POST(self):
        global METRICS
        try:
            length = int(self.headers.get("Content-Length", "-1"))
            if length < 0 or length > 262144:
                self.reply(413, {})
                return
            raw = self.rfile.read(length)
            if FIXTURES and self.path == "/metrics":
                with LOCK:
                    METRICS = raw.decode("utf-8")
                self.reply(204, "")
                return
            if self.path not in ("/critical", "/operations"):
                self.reply(404, {})
                return
            body = json.loads(raw)
            records = []
            for alert in body.get("alerts", [])[:50]:
                labels = alert.get("labels", {})
                safe = {
                    k: str(labels[k])[:128]
                    for k in ("alertname", "service", "env", "severity")
                    if k in labels
                }
                records.append(
                    {
                        "receiver": self.path[1:],
                        "status": alert.get("status"),
                        "labels": safe,
                        "receivedAt": datetime.now(timezone.utc).isoformat(),
                    }
                )
            with LOCK:
                RECEIPTS.extend(records)
                output = os.environ.get("ALERT_RECEIPT_FILE")
                if output:
                    with Path(output).open("a", encoding="utf-8") as target:
                        for record in records:
                            target.write(json.dumps(record) + "\n")
            self.reply(200, {})
        except (ValueError, UnicodeError, TypeError):
            self.reply(400, {})


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
