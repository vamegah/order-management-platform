"""Prepare short-lived workload identities and captured noop payments in an owned local drill only."""
import argparse, json, os, re, secrets, subprocess, uuid
from pathlib import Path
from urllib.request import Request, build_opener, ProxyHandler, HTTPRedirectHandler
from urllib.parse import urlencode
from urllib.error import HTTPError

ROOT = Path(__file__).resolve().parents[1]


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None


def prepare(args):
    if os.environ.get("OMP_ACCEPTANCE_FIXTURES") != "true" or not re.fullmatch(
        r"omp-operations-[a-z0-9]{6,24}", args.project
    ):
        raise ValueError("Explicit isolated operations fixture context required")
    destination = args.directory
    if (
        not destination.is_absolute()
        or destination.is_symlink()
        or destination.exists()
    ):
        raise ValueError("Use a fresh absolute private fixture directory")

    def docker(*parts):
        return subprocess.check_output(
            [args.docker, *parts], text=True, encoding="utf-8", timeout=30
        )

    ids = docker(
        "ps", "-q", "--filter", "label=com.docker.compose.project=" + args.project
    ).split()
    if not ids:
        raise ValueError("Owned fixture stack is absent")
    items = json.loads(docker("inspect", *ids))
    services = {}
    for item in items:
        labels = item["Config"].get("Labels") or {}
        if labels.get("com.omp.acceptance") != "operations":
            raise ValueError("Refusing an unowned fixture service")
        services[labels.get("com.docker.compose.service")] = item
    for name in ("keycloak", "payment-service", "traefik"):
        if name not in services:
            raise ValueError("Required fixture service absent: " + name)

    def configuration(name):
        return dict(
            value.split("=", 1)
            for value in services[name]["Config"].get("Env", [])
            if "=" in value
        )

    if configuration("payment-service").get("PAYMENT_PROVIDER") != "noop":
        raise ValueError(
            "Captured fixtures require the local noop provider; external charges are forbidden"
        )

    def origin(name, port):
        binding = services[name]["NetworkSettings"]["Ports"][str(port) + "/tcp"][0]
        if binding["HostIp"] != "127.0.0.1":
            raise ValueError("Fixture access must be loopback only")
        return "http://127.0.0.1:" + binding["HostPort"]

    keycloak = origin("keycloak", 8080)
    ingress = origin("traefik", 80)
    opener = build_opener(ProxyHandler({}), NoRedirect())

    def call(
        url,
        body=None,
        bearer=None,
        host="keycloak.localhost",
        expected=(200,),
        method=None,
        form=False,
    ):
        headers = {"Host": host}
        if bearer:
            headers["Authorization"] = "Bearer " + bearer
        raw = None
        if body is not None:
            raw = urlencode(body).encode() if form else json.dumps(body).encode()
            headers["Content-Type"] = (
                "application/x-www-form-urlencoded" if form else "application/json"
            )
        try:
            with opener.open(
                Request(url, data=raw, headers=headers, method=method), timeout=15
            ) as response:
                if response.status not in expected:
                    raise RuntimeError("Fixture response status rejected")
                data = response.read(1048577)
                if len(data) > 1048576:
                    raise ValueError("Oversized fixture response")
                return json.loads(data) if data else None
        except HTTPError as error:
            raise RuntimeError(
                "Isolated fixture request rejected: " + str(error.code)
            ) from None

    environment = configuration("keycloak")
    username = environment.get(
        "KEYCLOAK_ADMIN", environment.get("KC_BOOTSTRAP_ADMIN_USERNAME")
    )
    password = environment.get(
        "KEYCLOAK_ADMIN_PASSWORD", environment.get("KC_BOOTSTRAP_ADMIN_PASSWORD")
    )
    if not username or not password:
        raise ValueError("Isolated master administrator is unavailable")
    admin = call(
        keycloak + "/realms/master/protocol/openid-connect/token",
        {
            "grant_type": "password",
            "client_id": "admin-cli",
            "username": username,
            "password": password,
        },
        form=True,
    )["access_token"]
    base = keycloak + "/admin/realms/omp"
    role = call(base + "/roles/admin", bearer=admin)
    scopes = {scope["name"] for scope in call(base + "/client-scopes", bearer=admin)}
    clients = []
    customer = "00000000-0000-0000-0000-000000000101"
    for index in range(32):
        identifier = "ops-load-" + secrets.token_hex(6) + "-" + str(index)
        secret = secrets.token_urlsafe(32)
        record = {
            "clientId": identifier,
            "secret": secret,
            "protocol": "openid-connect",
            "enabled": True,
            "publicClient": False,
            "serviceAccountsEnabled": True,
            "standardFlowEnabled": False,
            "directAccessGrantsEnabled": False,
            "fullScopeAllowed": False,
            "defaultClientScopes": [
                name for name in ("roles", "profile", "basic") if name in scopes
            ],
            "protocolMappers": [
                {
                    "name": "audience",
                    "protocol": "openid-connect",
                    "protocolMapper": "oidc-audience-mapper",
                    "config": {
                        "included.custom.audience": "omp-api",
                        "access.token.claim": "true",
                        "id.token.claim": "false",
                    },
                },
                {
                    "name": "customer",
                    "protocol": "openid-connect",
                    "protocolMapper": "oidc-hardcoded-claim-mapper",
                    "config": {
                        "claim.name": "customer_id",
                        "claim.value": customer,
                        "jsonType.label": "String",
                        "access.token.claim": "true",
                        "id.token.claim": "false",
                    },
                },
            ],
        }
        call(base + "/clients", record, admin, expected=(201,))
        client = call(
            base + "/clients?" + urlencode({"clientId": identifier}), bearer=admin
        )
        if len(client) != 1:
            raise ValueError("Workload fixture client is ambiguous")
        client_id = client[0]["id"]
        account = call(
            base + "/clients/" + client_id + "/service-account-user", bearer=admin
        )
        call(
            base + "/users/" + account["id"] + "/role-mappings/realm",
            [role],
            admin,
            expected=(204,),
        )
        call(
            base + "/clients/" + client_id + "/scope-mappings/realm",
            [role],
            admin,
            expected=(204,),
        )
        clients.append({"clientId": identifier, "clientSecret": secret})
    realm = json.loads(
        (ROOT / "infrastructure/keycloak/import/omp-realm.json").read_text(
            encoding="utf-8"
        )
    )
    alice = next(user for user in realm["users"] if user["username"] == "alice")
    alice_password = next(
        c["value"] for c in alice["credentials"] if c["type"] == "password"
    )
    token = call(
        keycloak + "/realms/omp/protocol/openid-connect/token",
        {
            "grant_type": "password",
            "client_id": "postman",
            "username": "alice",
            "password": alice_password,
        },
        form=True,
    )["access_token"]
    fixtures = {}
    for mode in ("load", "soak"):
        payments = []
        for index in range(10):
            charge = call(
                ingress + "/api/v1/payments",
                {
                    "orderId": str(uuid.uuid4()),
                    "amountMinor": 101,
                    "currency": "USD",
                    "idempotencyKey": "ops-fixture-" + str(uuid.uuid4()),
                },
                token,
                host="payment-service.localhost",
            )
            if charge.get("result") != "confirmed" or not charge.get("paymentId"):
                raise RuntimeError("Noop payment fixture was not captured")
            payments.append({"id": charge["paymentId"], "amountMinor": 101})
        fixtures[mode] = {
            "customerId": customer,
            "productId": "00000000-0000-0000-0000-000000000204",
            "refundPayments": payments,
            "provider": "noop",
        }
    destination.mkdir(mode=0o700, parents=True)
    os.chmod(destination, 0o700)

    def write(name, data):
        fd = os.open(destination / name, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            json.dump(data, stream)

    write(
        "identities.json",
        {
            "tokenUrl": "http://keycloak:8080/realms/omp/protocol/openid-connect/token",
            "clients": clients,
        },
    )
    for name, data in fixtures.items():
        write(name + ".json", data)
    print(
        json.dumps(
            {
                "isolatedWorkloadClients": 32,
                "capturedNoopPayments": 20,
                "externalMoneyCalls": 0,
                "credentialValuesPrinted": False,
            }
        )
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True)
    parser.add_argument("--docker", default="docker")
    parser.add_argument("--directory", type=Path, required=True)
    prepare(parser.parse_args())


if __name__ == "__main__":
    main()
