import base64, concurrent.futures, json, os, sys, time, re, ipaddress, secrets
from pathlib import Path
from urllib.request import Request, build_opener, ProxyHandler, HTTPRedirectHandler
from urllib.error import HTTPError
from urllib.parse import urlencode


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None


opener = build_opener(ProxyHandler({}), NoRedirect())
secret = Path("/fixture/keycloak_admin_password").read_text().strip()
client_secret = Path("/fixture/acceptance_client_secret").read_text().strip()
keycloak = "http://keycloak:8080"
ips = os.environ["TARGET_IPS"].split(",")
assert os.environ.get("OMP_ACCEPTANCE_FIXTURES") == "true"
assert re.fullmatch(
    r"omp-operations-[a-z0-9]{6,24}", os.environ.get("OMP_ACCEPTANCE_PROJECT", "")
)
assert len(ips) == 2 and all(ipaddress.ip_address(ip).is_private for ip in ips)


def call(url, body=None, headers=None, method=None):
    headers = headers or {}
    if isinstance(body, dict):
        body = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    request = Request(url, data=body, headers=headers, method=method)
    try:
        with opener.open(request, timeout=10) as response:
            return response.status, response.read(1048576), dict(response.headers)
    except HTTPError as error:
        return error.code, error.read(65536), dict(error.headers)


def token(client=None):
    if client is None:
        body = urlencode(
            {
                "grant_type": "password",
                "client_id": "admin-cli",
                "username": "admin",
                "password": secret,
            }
        ).encode()
        headers = {}
    else:
        body = urlencode({"grant_type": "client_credentials"}).encode()
        headers = {
            "Authorization": "Basic "
            + base64.b64encode((client + ":" + client_secret).encode()).decode()
        }
    headers["Content-Type"] = "application/x-www-form-urlencoded"
    code, raw, _ = call(
        keycloak
        + "/realms/"
        + ("master" if client is None else "omp")
        + "/protocol/openid-connect/token",
        body,
        headers,
    )
    assert code == 200, "Issuer rejected isolated fixture credentials"
    return json.loads(raw)["access_token"]


mode = sys.argv[1]
if mode == "baseline":
    auth = {"Authorization": "Bearer " + token()}
    code, _, _ = call(
        keycloak + "/admin/realms",
        {
            "realm": "omp",
            "enabled": True,
            "sslRequired": "none",
            "accessTokenLifespan": 300,
        },
        auth.copy(),
    )
    assert code == 201, "Fixture realm already exists or creation failed"
    code, _, _ = call(
        keycloak + "/admin/realms/omp/client-scopes",
        {
            "name": "metrics.read",
            "protocol": "openid-connect",
            "attributes": {"include.in.token.scope": "true"},
        },
        auth.copy(),
    )
    assert code == 201
    for client, audience, metrics in [
        ("accepted-client", "omp-api", False),
        ("metrics-client", "omp-api", True),
        ("foreign-client", "foreign-api", True),
        ("recovery-client", "omp-api", False),
    ]:
        config = {
            "clientId": client,
            "protocol": "openid-connect",
            "enabled": True,
            "secret": client_secret,
            "publicClient": False,
            "serviceAccountsEnabled": True,
            "standardFlowEnabled": False,
            "directAccessGrantsEnabled": False,
            "defaultClientScopes": ["basic"] + (["metrics.read"] if metrics else []),
            "protocolMappers": [
                {
                    "name": "audience",
                    "protocol": "openid-connect",
                    "protocolMapper": "oidc-audience-mapper",
                    "config": {
                        "included.custom.audience": audience,
                        "access.token.claim": "true",
                        "id.token.claim": "false",
                    },
                }
            ],
        }
        code, _, _ = call(keycloak + "/admin/realms/omp/clients", config, auth.copy())
        assert code == 201, "Isolated client provisioning failed"
    accepted = token("accepted-client")
    metric = token("metrics-client")
    foreign = token("foreign-client")
    trace = secrets.token_hex(16)
    code, _, _ = call(
        "http://" + ips[0] + ":8081/api/v1/products",
        headers={
            "Authorization": "Bearer " + accepted,
            "traceparent": "00-" + trace + "-" + secrets.token_hex(8) + "-01",
        },
    )
    assert code == 200, "Authenticated exemplar request failed"
    exemplar_seen = False
    for ip in ips:
        code, _, _ = call(
            "http://" + ip + ":8081/api/v1/products",
            headers={
                "Authorization": "Bearer " + foreign,
                "X-User-Role": "admin",
                "X-Forwarded-For": "127.0.0.1",
            },
        )
        assert code == 401, "Foreign audience was accepted"
        code, _, _ = call(
            "http://" + ip + ":8081/actuator/prometheus",
            headers={"Authorization": "Bearer " + accepted},
        )
        assert code == 403, "Unscoped metrics access was accepted"
        code, metric_body, _ = call(
            "http://" + ip + ":8081/actuator/prometheus",
            headers={
                "Authorization": "Bearer " + metric,
                "Accept": "application/openmetrics-text; version=1.0.0; charset=utf-8",
            },
        )
        assert code == 200, "Scoped metrics access was rejected"
        metric_text = metric_body.decode()
        assert (
            "http_server_requests_seconds_bucket{" in metric_text
            and 'le="1.0"' in metric_text
        ), "Latency SLO histogram is absent"
        exemplar_seen |= trace in metric_text
    assert (
        exemplar_seen
    ), "HTTP metric has no exemplar linked to the real incoming trace"

    def burst(i):
        code, body, headers = call(
            "http://" + ips[i % len(ips)] + ":8081/api/v1/products",
            headers={
                "Authorization": "Bearer " + metric,
                "X-Forwarded-For": "192.0.2." + str(i),
            },
        )
        if code == 429:
            assert (
                int(headers["Retry-After"]) >= 1
                and json.loads(body)["code"] == "RATE_LIMITED"
            )
        return code

    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        statuses = list(pool.map(burst, range(16)))
    assert (
        statuses.count(200) == 2 and statuses.count(429) == 14
    ), "Replica quota mismatch: " + str(statuses)
    print(
        json.dumps(
            {
                "phase": "real-issuer-and-two-api-replicas",
                "passed": True,
                "replicas": len(ips),
                "foreignAudienceDenied": True,
                "unscopedMetricsDenied": True,
                "scopedMetricsAccepted": True,
                "latencySloHistogram": True,
                "traceExemplar": True,
                "clusterAccepted": 2,
                "clusterLimited": 14,
                "spoofedForwardingIgnored": True,
            }
        )
    )
else:
    value = token("accepted-client" if mode == "outage" else "recovery-client")
    start = time.monotonic()
    code, body, headers = call(
        "http://" + ips[0] + ":8081/api/v1/products",
        headers={"Authorization": "Bearer " + value},
    )
    elapsed = time.monotonic() - start
    if mode == "outage":
        assert (
            code == 503
            and elapsed < 2
            and json.loads(body)["code"] == "QUOTA_UNAVAILABLE"
        )
    else:
        assert code == 200, "Fresh identity did not recover after Redis resumed"
    print(
        json.dumps(
            {
                "phase": "quota-" + mode,
                "passed": True,
                "status": code,
                "seconds": round(elapsed, 3),
            }
        )
    )
