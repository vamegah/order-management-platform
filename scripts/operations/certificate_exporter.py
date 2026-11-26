"""Read public leaf PEM certificates only; expose no private key or certificate subject."""
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
import ssl, time

DIRECTORY = Path("/certificates")


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        if self.path != "/metrics":
            self.send_error(404)
            return
        lines = ["# TYPE omp_tls_certificate_not_after_seconds gauge"]
        # A fixed bounded catalogue prevents accidental recursive private-key or user metadata reads.
        for name in (
            "order-service",
            "inventory-service",
            "payment-service",
            "legacy-bank-service",
        ):
            source = DIRECTORY / (name + ".crt")
            try:
                certificate = ssl._ssl._test_decode_cert(str(source))
                expiry = ssl.cert_time_to_seconds(certificate["notAfter"])
                lines.append(
                    'omp_tls_certificate_not_after_seconds{service="'
                    + name
                    + '"} '
                    + str(expiry)
                )
            except (OSError, ssl.SSLError, KeyError):
                lines.append(
                    'omp_tls_certificate_not_after_seconds{service="' + name + '"} 0'
                )
        data = ("\n".join(lines) + "\n").encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; version=0.0.4")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


if __name__ == "__main__":
    HTTPServer(("0.0.0.0", 9188), Handler).serve_forever()
