"""Measure the declared k6 load and soak profiles against an explicitly owned disposable stack.

Fixture identities and independently captured refund payments must be provisioned before running.
This command does not adopt production projects or manufacture acceptance from missing samples.
"""
import argparse, hashlib, json, os, re, subprocess, time, uuid
from pathlib import Path
from operations.evidence import evidence

ROOT = Path(__file__).resolve().parents[1]
REQUIRED = {
    "traefik",
    "keycloak",
    "postgres",
    "product-service",
    "customer-service",
    "graphql-gateway",
    "order-service",
    "inventory-service",
    "payment-service",
    "notification-service",
}


def fixture(path, identities=False):
    if not path.is_absolute() or path.is_symlink() or not path.is_file():
        raise ValueError("An absolute mounted acceptance fixture file is required")
    if path.stat().st_size > 1048576:
        raise ValueError("Fixture exceeds its bounded input size")
    if identities and os.name == "posix" and path.stat().st_mode & 0o077:
        raise ValueError("Load credentials must be accessible only to their owner")
    data = json.loads(path.read_text(encoding="utf-8"))
    if identities:
        clients = data.get("clients", [])
        if (
            len(clients) < 32
            or len(clients) > 64
            or len({c.get("clientId") for c in clients}) != len(clients)
        ):
            raise ValueError("Prepare 32 to 64 distinct isolated workload clients")
        if not all(c.get("clientId") and c.get("clientSecret") for c in clients):
            raise ValueError("Load workload credentials are incomplete")
        if (
            data.get("tokenUrl")
            != "http://keycloak:8080/realms/omp/protocol/openid-connect/token"
        ):
            raise ValueError("Only the private acceptance issuer is supported")
    else:
        payments = data.get("refundPayments", [])
        if (
            not data.get("customerId")
            or not data.get("productId")
            or len(payments) < 10
        ):
            raise ValueError(
                "Prepare checkout fixtures and at least ten captured refund payments per profile"
            )
        uuid.UUID(data["customerId"])
        uuid.UUID(data["productId"])
        for payment in payments:
            uuid.UUID(payment["id"])
            if (
                type(payment.get("amountMinor")) is not int
                or payment["amountMinor"] < 1
            ):
                raise ValueError(
                    "Refund fixtures require a positive integer minor-unit amount"
                )
        if len({p.get("id") for p in payments}) != len(payments):
            raise ValueError("A captured payment must appear only once in a profile")
    return data


def execute(args):
    if not re.fullmatch(r"omp-operations-[a-z0-9]{6,24}", args.project):
        raise ValueError("An explicitly isolated operations project is required")
    identities = fixture(args.identities, identities=True)
    load, soak = fixture(args.load_data), fixture(args.soak_data)
    if {p["id"] for p in load["refundPayments"]} & {
        p["id"] for p in soak["refundPayments"]
    }:
        raise ValueError("Load and soak must use independent captured refund fixtures")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    report = {"passed": False, "phases": [], **evidence(args.docker, ROOT)}
    objective = json.loads((ROOT / "docs/operations-slos.json").read_text())
    report["objectives"] = objective
    if (
        report["hardware"]["NCPU"] < objective["referenceHost"]["minimumCpus"]
        or report["hardware"]["MemTotal"]
        < objective["referenceHost"]["minimumMemoryMiB"] * 1048576
    ):
        raise ValueError("The declared reference-host minimum is not available")
    ids = subprocess.check_output(
        [
            args.docker,
            "ps",
            "-q",
            "--filter",
            "label=com.docker.compose.project=" + args.project,
        ],
        text=True,
    ).split()
    if not ids:
        raise ValueError("The prepared disposable stack is absent")
    containers = json.loads(
        subprocess.check_output([args.docker, "inspect", *ids], text=True)
    )
    services = {}
    for item in containers:
        labels = item["Config"].get("Labels") or {}
        if labels.get("com.omp.acceptance") != "operations":
            raise ValueError("Refusing an unowned performance target")
        service = labels.get("com.docker.compose.service")
        services[service] = item["Image"]
    if not REQUIRED <= set(services):
        raise ValueError("The complete load topology is required")
    report["ownedImages"] = services
    lock = json.loads((ROOT / "infrastructure/release/images.lock.json").read_text())[
        "toolsAndBases"
    ]
    image = lock["grafana/k6:1.6.1"]
    secrets = [c["clientSecret"] for c in identities["clients"]]
    for name, data in (("load", args.load_data), ("soak", args.soak_data)):
        destination = output / name
        destination.mkdir(exist_ok=True)
        command = [
            args.docker,
            "run",
            "--rm",
            "--user",
            "0:0",
            "--label",
            "com.omp.task=section7",
            "--network",
            args.project + "_edge",
        ]
        for source, target, readonly in (
            (ROOT / "scripts/performance", "/scripts", True),
            (args.identities, "/fixture/identities.json", True),
            (data, "/fixture/data.json", True),
            (destination, "/results", False),
        ):
            command += [
                "--mount",
                "type=bind,source="
                + str(source)
                + ",target="
                + target
                + (",readonly" if readonly else ""),
            ]
        for key, value in {
            "OMP_API_URL": "http://traefik",
            "OMP_PRODUCT_HOST": "product-service.localhost",
            "OMP_ORDER_HOST": "order-service.localhost",
            "OMP_PAYMENT_HOST": "payment-service.localhost",
            "OMP_GRAPHQL_HOST": "graphql.localhost",
            "OMP_ACCEPTANCE_PROJECT": args.project,
            "OMP_IDENTITIES_FILE": "/fixture/identities.json",
            "OMP_LOAD_DATA_FILE": "/fixture/data.json",
            "OMP_SOAK": str(name == "soak").lower(),
        }.items():
            command += ["-e", key + "=" + value]
        started = time.monotonic()
        result = subprocess.run(
            [*command, image, "run", "/scripts/mixed.js"],
            capture_output=True,
            text=True,
            timeout=780 if name == "soak" else 300,
        )
        diagnostics = result.stdout + result.stderr
        for value in secrets:
            diagnostics = diagnostics.replace(value, "[REDACTED]")
        (destination / "k6.log").write_text(diagnostics, encoding="utf-8")
        summary_path = destination / "k6-summary.json"
        summary = json.loads(summary_path.read_text()) if summary_path.is_file() else {}
        metrics = summary.get("metrics", {})
        requests = metrics.get("http_reqs", {}).get("values", {}).get("count", 0)
        # Inspect only counts; a load run cannot pass after corrupting stock or accepted references.
        database = next(
            item["Id"]
            for item in containers
            if item["Config"]["Labels"].get("com.docker.compose.service") == "postgres"
        )
        invariant_result = subprocess.run(
            [
                args.docker,
                "exec",
                "-i",
                database,
                "psql",
                "-XAtq",
                "--set=ON_ERROR_STOP=1",
                "-U",
                "omp_admin",
                "-d",
                "omp",
            ],
            input=(ROOT / "scripts/operations/verify-conservation.sql").read_text(
                encoding="utf-8"
            ),
            capture_output=True,
            text=True,
            encoding="utf-8",
            timeout=30,
        )
        invariants = {}
        if invariant_result.returncode == 0:
            for line in invariant_result.stdout.splitlines():
                key, value = line.rsplit("|", 1)
                invariants[key] = int(value)
        conservation = len(invariants) == 4 and all(
            value == 0 for value in invariants.values()
        )
        # Confirm money outcomes in durable state as well as HTTP responses.
        data_value = load if name == "load" else soak
        payment_ids = ",".join("'" + p["id"] + "'::uuid" for p in data_value["refundPayments"])
        money = subprocess.check_output(
            [args.docker, "exec", database, "psql", "-XAtq", "--set=ON_ERROR_STOP=1",
             "-U", "omp_admin", "-d", "omp", "-c",
             "SELECT count(*),count(DISTINCT payment_id),coalesce(sum(amount*100),0)::bigint,bool_and(status='COMPLETED' AND currency='USD') FROM payments.refund "
             "WHERE payment_id=ANY(ARRAY[" + payment_ids + "])"] ,
            text=True, timeout=30).strip()
        expected_money = str(len(data_value["refundPayments"])) + "|" + str(len(data_value["refundPayments"])) + "|" + str(sum(p["amountMinor"] for p in data_value["refundPayments"])) + "|t"
        money_passed = money == expected_money
        drain_started = time.monotonic()
        drain_limit = objective["recovery"]["maximumBacklogDrainSeconds"]
        backlog_sql = "SELECT " + "+".join(
            "(SELECT count(*) FROM " + schema + ".outbox_event WHERE status<>'PUBLISHED')"
            for schema in ("products", "customers", "orders", "inventory", "payments")) + "+(SELECT count(*) FROM notifications.notification_dispatch WHERE state<>'PUBLISHED')"
        while True:
            backlog = int(subprocess.check_output(
                [args.docker, "exec", database, "psql", "-XAtq", "--set=ON_ERROR_STOP=1",
                 "-U", "omp_admin", "-d", "omp", "-c", backlog_sql], text=True, timeout=30).strip())
            if backlog == 0 or time.monotonic() - drain_started >= drain_limit:
                break
            time.sleep(1)
        drain_seconds = time.monotonic() - drain_started
        minimum = 1200 if name == "load" else 6000
        passed = (
            result.returncode == 0
            and conservation
            and money_passed
            and backlog == 0
            and drain_seconds <= drain_limit
            and requests >= minimum
            and bool(metrics)
            and all(
                t.get("ok") is True
                for m in metrics.values()
                for t in m.get("thresholds", {}).values()
            )
        )
        phase = {
            "name": name,
            "passed": passed,
            "seconds": round(time.monotonic() - started, 2),
            "httpRequests": requests,
            "invariants": invariants,
            "conservationPassed": conservation,
            "refundConservationPassed": money_passed,
            "completedFixtureRefunds": len(data_value["refundPayments"]) if money_passed else None,
            "pendingOrFailedDispatches": backlog,
            "backlogDrainSeconds": round(drain_seconds, 2),
            "summarySha256": hashlib.sha256(summary_path.read_bytes()).hexdigest()
            if summary_path.is_file()
            else None,
        }
        if not passed:
            phase["remediation"] = {
                "priority": "P1",
                "owner": "platform maintainers",
                "requiredAction": "Inspect the retained bounded-load measurements and fix the failed objective; rerun the same declared profile before release.",
            }
        report["phases"].append(phase)
        (output / "performance.json").write_text(
            json.dumps(report, indent=2), encoding="utf-8"
        )
        print(json.dumps(phase), flush=True)
    report["passed"] = len(report["phases"]) == 2 and all(
        p["passed"] for p in report["phases"]
    )
    (output / "performance.json").write_text(
        json.dumps(report, indent=2), encoding="utf-8"
    )
    if not report["passed"]:
        raise AssertionError(
            "Measured performance objectives failed; prioritized remediation retained"
        )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True)
    parser.add_argument("--docker", default="docker")
    parser.add_argument("--identities", type=Path, required=True)
    parser.add_argument("--load-data", type=Path, required=True)
    parser.add_argument("--soak-data", type=Path, required=True)
    parser.add_argument(
        "--output", type=Path, default=ROOT / "coverage/operations/performance"
    )
    execute(parser.parse_args())


if __name__ == "__main__":
    main()
