"""Exercise issuer HTTP behavior and atomic projections using an isolated server."""

import base64
from dataclasses import replace
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.parse import parse_qs, unquote_plus

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import project_service_token as projector


class ServiceTokenProjectionTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.secret = self.root / "credential"
        self.secret.write_text("test-only-client-secret", encoding="utf-8")
        self.output = self.root / "identity" / "access.jwt"
        self.requests = []
        self.status, self.headers = 200, {}
        self.claims = {"iss": "https://issuer.example/realms/test", "azp": "order-recovery",
                       "scope": "order.reconcile", "aud": ["inventory-service", "payment-service"],
                       "exp": int(time.time()) + 300}
        self.payload = {"access_token": self.jwt(), "expires_in": 300, "token_type": "Bearer"}
        self.raw = None
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
                owner.requests.append((self.path, dict(self.headers), body))
                value = owner.raw if owner.raw is not None else json.dumps(owner.payload).encode()
                self.send_response(owner.status)
                for name, header in owner.headers.items():
                    self.send_header(name, header)
                self.send_header("Content-Length", str(len(value)))
                self.end_headers()
                self.wfile.write(value)

            def do_GET(self):
                owner.requests.append((self.path, dict(self.headers), b""))
                self.send_response(200)
                self.end_headers()

            def log_message(self, *_):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.close_server)
        self.settings = projector.Settings(
            f"http://127.0.0.1:{self.server.server_port}/token", self.claims["iss"],
            self.claims["azp"], self.secret, self.output, True)

    def close_server(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(5)

    def jwt(self, **changes):
        payload = base64.urlsafe_b64encode(json.dumps({**self.claims, **changes}).encode()).decode().rstrip("=")
        return "eyJhbGciOiJSUzI1NiJ9." + payload + ".testSignature"

    def assert_no_partial_write(self, action, exception=Exception):
        self.output.parent.mkdir(exist_ok=True)
        self.output.write_text("previous-projection", encoding="ascii")
        with self.assertRaises(exception):
            action()
        self.assertEqual("previous-projection", self.output.read_text())
        self.assertEqual([self.output], list(self.output.parent.iterdir()))

    def test_client_credentials_grant_projects_a_private_file(self):
        delay = projector.project_once(self.settings)
        self.assertEqual(self.payload["access_token"], self.output.read_text())
        self.assertGreaterEqual(delay, 1)
        self.assertLessEqual(delay, 60)
        path, headers, body = self.requests[0]
        self.assertEqual("/token", path)
        authorization = base64.b64decode(headers["Authorization"].removeprefix("Basic ")).decode()
        self.assertEqual(["order-recovery", "test-only-client-secret"],
                         [unquote_plus(value) for value in authorization.split(":", 1)])
        self.assertEqual({"grant_type": ["client_credentials"], "scope": ["order.reconcile"]}, parse_qs(body.decode()))
        self.assertNotIn(b"test-only-client-secret", body)
        if os.name == "posix":
            self.assertEqual(0o600, self.output.stat().st_mode & 0o777)

    def test_rotation_reloads_the_credential_and_replaces_the_token(self):
        projector.project_once(self.settings)
        self.secret.write_text("rotated-secret", encoding="utf-8")
        self.payload["access_token"] = self.jwt(jti="second-issued-token")
        projector.project_once(self.settings)
        self.assertEqual(self.payload["access_token"], self.output.read_text())
        authorization = self.requests[-1][1]["Authorization"]
        self.assertIn(b"rotated-secret", base64.b64decode(authorization.removeprefix("Basic ")))
        self.assertEqual([self.output], list(self.output.parent.iterdir()))

    def test_invalid_jwt_metadata_never_replaces_the_previous_projection(self):
        values = [None, "opaque", "a.b.c", "x" * 16385,
                  self.jwt(iss="https://foreign.example"), self.jwt(azp="foreign-client"),
                  self.jwt(scope="openid"), self.jwt(aud=["inventory-service"]),
                  self.jwt(aud=None), self.jwt(exp=0), self.jwt(exp=True),
                  self.jwt(exp="9999999999"), self.jwt(exp=int(time.time()) + 10)]
        for token in values:
            with self.subTest(token_case=values.index(token)):
                self.payload["access_token"] = token
                self.assert_no_partial_write(lambda: projector.project_once(self.settings))

    def test_invalid_issuer_response_never_replaces_the_previous_projection(self):
        original = dict(self.payload)
        values = [[], {}, {**original, "token_type": "Basic"},
                  *[{**original, "expires_in": value} for value in (None, 0, True, 1.5, "300", 86401)]]
        for value in values:
            with self.subTest(response=type(value).__name__, lifetime=value.get("expires_in") if isinstance(value, dict) else None):
                self.payload = value
                self.assert_no_partial_write(lambda: projector.project_once(self.settings))
        for value in (b"not JSON", b"x" * 65537):
            self.raw = value
            self.assert_no_partial_write(lambda: projector.project_once(self.settings))

    def test_redirects_and_issuer_failures_are_not_followed_or_projected(self):
        for status in (301, 302, 303, 307, 308, 401, 500):
            with self.subTest(status=status):
                self.status = status
                self.headers = {"Location": self.settings.token_url + "/credential-trap"}
                self.assert_no_partial_write(lambda: projector.project_once(self.settings), HTTPError)
        self.assertEqual(7, len(self.requests))
        self.assertTrue(all(path == "/token" for path, _, _ in self.requests))

    def test_plaintext_requires_an_explicit_development_setting(self):
        self.assert_no_partial_write(lambda: projector.project_once(replace(self.settings, allow_development_http=False)), ValueError)
        self.assertEqual([], self.requests)

    def test_credentials_and_projection_paths_are_bounded_and_distinct(self):
        for value in (b"", b" ", b"x" * 4097, b"\xff"):
            self.secret.write_bytes(value)
            self.assert_no_partial_write(lambda: projector.project_once(self.settings))
        self.assertEqual([], self.requests)
        with self.assertRaises(ValueError):
            replace(self.settings, output=self.secret).validate()

    def test_url_credentials_queries_and_fragments_are_rejected_before_discovery(self):
        for value in ("https://user:secret@issuer.example/token", "https://issuer.example/token?secret=value",
                      "https://issuer.example/token#fragment", "file:///tmp/token", "https:///token"):
            with self.subTest(url_case=value.split(":")[0]):
                with self.assertRaises(ValueError):
                    replace(self.settings, token_url=value).validate()
        self.assertEqual([], self.requests)

    def test_failed_atomic_replace_keeps_the_previous_projection_and_cleans_its_temp_file(self):
        with patch.object(projector.os, "replace", side_effect=OSError("injected storage failure")):
            self.assert_no_partial_write(lambda: projector.project_once(self.settings), OSError)

    def test_command_failure_does_not_print_credentials_or_issuer_payloads(self):
        self.status = 500
        self.raw = b"test-only-client-secret bearer-provider-secret"
        environment = {**os.environ, "SERVICE_TOKEN_URL": self.settings.token_url,
                       "SERVICE_TOKEN_ISSUER": self.settings.issuer,
                       "SERVICE_TOKEN_CLIENT_ID": self.settings.client_id,
                       "SERVICE_TOKEN_CLIENT_SECRET_FILE": str(self.secret),
                       "SERVICE_TOKEN_OUTPUT": str(self.output), "SERVICE_TOKEN_ALLOW_HTTP": "development"}
        result = subprocess.run([sys.executable, projector.__file__, "--once"],
                                env=environment, text=True, capture_output=True, timeout=15)
        self.assertEqual(1, result.returncode)
        self.assertIn("HTTPError", result.stdout)
        self.assertNotIn("test-only-client-secret", result.stdout + result.stderr)
        self.assertNotIn("bearer-provider-secret", result.stdout + result.stderr)
        self.assertNotIn(self.settings.token_url, result.stdout + result.stderr)
        self.assertFalse(self.output.exists())


if __name__ == "__main__":
    unittest.main()
