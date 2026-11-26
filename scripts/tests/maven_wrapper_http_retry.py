"""Real loopback-only HTTP 404/recovery fixture for the checksum-verifying Maven wrapper."""
from http.server import BaseHTTPRequestHandler, HTTPServer
import os
from pathlib import Path
import subprocess
import sys
from threading import Thread


def main():
    fixture = Path(sys.argv[1])
    checksum = sys.argv[2]
    requests = []
    archive = (fixture / "maven.zip").read_bytes()

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_GET(self):
            requests.append(self.path)
            if len(requests) == 1:
                self.send_response(404)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            self.send_response(200)
            self.send_header("Content-Length", str(len(archive)))
            self.end_headers()
            self.wfile.write(archive)

    with HTTPServer(("127.0.0.1", 0), Handler) as server:
        thread = Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            properties = fixture / "project/.mvn/wrapper/maven-wrapper.properties"
            # Only this owned fixture uses HTTP; the repository distribution remains HTTPS.
            properties.write_text(
                "distributionUrl=http://127.0.0.1:" + str(server.server_port)
                + "/org/apache/maven/apache-maven/test/apache-maven-test-bin.zip\n"
                + "distributionSha256Sum=" + checksum + "\n")
            result = subprocess.run(["bash", "mvnw", "--version"], cwd=fixture / "project",
                                    env={**os.environ, "PATH": str(fixture / "bin"),
                                         "MAVEN_USER_HOME": str(fixture / "http-retry-cache")},
                                    capture_output=True, text=True, timeout=30)
            if result.returncode or result.stdout.strip() != "verified-wrapper-fixture" or len(requests) != 2:
                raise AssertionError("Checksum-pinned HTTP retry failed: " + result.stderr[-1000:])
        finally:
            server.shutdown()
            thread.join(timeout=5)
    print("Retried a transient HTTP 404 and executed only the checksum-verified fixture.")


if __name__ == "__main__":
    main()
