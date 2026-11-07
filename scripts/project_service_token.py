"""Project short-lived client-credentials JWTs without logging credentials.

The HTTPS issuer is trusted here. Inventory/payment still verify JWT signatures,
issuer, expiry and authorization; metadata inspection is not JWT verification.
"""

import argparse
import base64
from dataclasses import dataclass
import json
import math
import os
from pathlib import Path
import random
import re
import signal
import tempfile
import threading
import time
from urllib.parse import quote_plus, urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


MAX_TOKEN_BYTES = 16384


class NoRedirects(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        return None


@dataclass(frozen=True)
class Settings:
    token_url: str
    issuer: str
    client_id: str
    secret_file: Path
    output: Path
    allow_development_http: bool = False
    scope: str = "order.reconcile"
    audiences: tuple = ("inventory-service", "payment-service")

    def validate(self):
        for value in (self.token_url, self.issuer):
            url = urlsplit(value)
            if (url.scheme not in ("https", "http") or not url.hostname
                    or url.username is not None or url.password is not None
                    or url.query or url.fragment
                    or (url.scheme == "http" and not self.allow_development_http)):
                raise ValueError("An HTTPS issuer and token endpoint are required")
        if not self.client_id or not self.scope or not self.audiences:
            raise ValueError("Client identity, scope and audiences are required")
        if self.output.resolve() == self.secret_file.resolve():
            raise ValueError("Credential and projection paths must differ")
        return self

    @classmethod
    def environment(cls):
        return cls(
            os.environ["SERVICE_TOKEN_URL"], os.environ["SERVICE_TOKEN_ISSUER"],
            os.environ["SERVICE_TOKEN_CLIENT_ID"],
            Path(os.environ["SERVICE_TOKEN_CLIENT_SECRET_FILE"]),
            Path(os.environ["SERVICE_TOKEN_OUTPUT"]),
            os.environ.get("SERVICE_TOKEN_ALLOW_HTTP") == "development",
        ).validate()


def bounded_read(path, limit):
    with path.open("rb") as source:
        value = source.read(limit + 1)
    if len(value) > limit:
        raise ValueError("Input exceeds its size limit")
    return value


def inspect_metadata(token, settings, now):
    if (not isinstance(token, str) or len(token.encode("utf-8")) > MAX_TOKEN_BYTES
            or not re.fullmatch(r"[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+", token)):
        raise ValueError("Invalid JWT projection format")
    payload = token.split(".")[1]
    claims = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
    if not isinstance(claims, dict):
        raise ValueError("Invalid JWT metadata")
    expiry = claims.get("exp")
    scopes = claims.get("scope")
    audiences = claims.get("aud")
    if isinstance(audiences, str):
        audiences = [audiences]
    if (type(expiry) is not int or expiry - now < 30
            or claims.get("iss") != settings.issuer
            or claims.get("azp") != settings.client_id
            or not isinstance(scopes, str) or settings.scope not in scopes.split()
            or not isinstance(audiences, list)
            or not all(audience in audiences for audience in settings.audiences)):
        raise ValueError("JWT metadata does not match workload identity")
    return expiry


def replace_projection(output, token):
    # Mount this directory read-only in the application, rather than a single
    # file: replacement must become visible to its next file read.
    output.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="ascii", newline="",
                                         prefix=".token-", dir=output.parent,
                                         delete=False) as target:
            temporary = Path(target.name)
            os.chmod(temporary, 0o600)
            target.write(token)
            target.flush()
            os.fsync(target.fileno())
        os.replace(temporary, output)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def project_once(settings, opener=None, clock=time.time):
    settings.validate()
    secret = bounded_read(settings.secret_file, 4096).decode("utf-8").strip()
    if not secret:
        raise ValueError("A client credential is required")
    credentials = quote_plus(settings.client_id, safe="") + ":" + quote_plus(secret, safe="")
    authorization = base64.b64encode(credentials.encode("utf-8")).decode("ascii")
    request = Request(settings.token_url,
                      data=urlencode({"grant_type": "client_credentials", "scope": settings.scope}).encode("ascii"),
                      headers={"Authorization": "Basic " + authorization,
                               "Content-Type": "application/x-www-form-urlencoded",
                               "Accept": "application/json"}, method="POST")
    with (opener or build_opener(NoRedirects())).open(request, timeout=10) as response:
        if response.status != 200:
            raise ValueError("The issuer rejected token projection")
        body = response.read(65537)
    if len(body) > 65536:
        raise ValueError("Issuer response exceeds its size limit")
    value = json.loads(body)
    if not isinstance(value, dict):
        raise ValueError("Invalid issuer response")
    lifetime = value.get("expires_in")
    if (str(value.get("token_type", "")).lower() != "bearer"
            or type(lifetime) is not int or lifetime < 30 or lifetime > 86400):
        raise ValueError("Invalid issuer token lifetime")
    now = clock()
    token = value.get("access_token")
    expiry = inspect_metadata(token, settings, now)
    remaining = min(expiry - now, lifetime)
    if not math.isfinite(remaining):
        raise ValueError("Invalid issuer token lifetime")
    replace_projection(settings.output, token)
    return max(1, min(60, remaining * random.uniform(0.65, 0.75)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--once", action="store_true")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    settings = Settings.environment()
    if args.check:
        inspect_metadata(bounded_read(settings.output, MAX_TOKEN_BYTES).decode("ascii"), settings, time.time())
        return 0
    stopped = threading.Event()
    for name in ("SIGINT", "SIGTERM"):
        signal.signal(getattr(signal, name), lambda *_: stopped.set())
    while not stopped.is_set():
        try:
            delay = project_once(settings)
            print("Workload token projection refreshed", flush=True)
        except Exception as failure:
            # Exception messages, URLs and issuer bodies can contain secrets.
            print("Workload token projection failed: " + type(failure).__name__, flush=True)
            if args.once:
                return 1
            delay = 5
        if args.once:
            return 0
        stopped.wait(delay)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as failure:
        print("Workload token projection failed: " + type(failure).__name__, flush=True)
        raise SystemExit(1)
