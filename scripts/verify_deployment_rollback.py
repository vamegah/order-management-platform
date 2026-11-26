"""Rehearse a failed configuration replacement and digest rollback in an owned local stack.

This is an isolated application recovery drill, not a production release or approval.
The production driver separately requires signed inputs and protected environment approval.
"""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile
import time
from operations.evidence import evidence
from operations.faults import inspect_container

ROOT = Path(__file__).resolve().parents[1]


def execute(args):
    # Complete all ownership checks before any mutation.
    services = {name: inspect_container(args.docker, args.project, name)
                for name in ("product-service", "postgres", "inventory-service", "payment-service")}

    def docker(*parts, timeout=30):
        return subprocess.check_output([args.docker, *parts], text=True, timeout=timeout)

    original = json.loads(docker("inspect", services["product-service"]))[0]
    image_id = original["Image"]
    if original.get("State", {}).get("Health", {}).get("Status") != "healthy":
        raise ValueError("A healthy accepted application is required before rollback rehearsal")
    report = {"passed": False, "cleanupPassed": False, "phases": [], **evidence(args.docker, ROOT)}
    image = json.loads(docker("image", "inspect", image_id))[0]
    if image["Config"].get("Labels", {}).get("org.opencontainers.image.revision") != report["sourceRevision"]:
        raise ValueError("Rollback image does not match the measured source revision")
    report.update(scope="isolated application configuration rollback", acceptedImageId=image_id,
                  productionPromotionExecuted=False, databaseMigrationsReversed=False)
    maximum = json.loads((ROOT / "docs/operations-slos.json").read_text())["recovery"]["maximumRtoSeconds"]

    def durable_state():
        # Only aggregate hashes/counts leave the database; no business payload is recorded.
        return docker("exec", services["postgres"], "psql", "-XAtq", "--set=ON_ERROR_STOP=1",
                      "-U", "omp_admin", "-d", "omp", "-c",
                      "SELECT count(*),md5(string_agg(row_to_json(p)::text,',' ORDER BY p.id)) FROM products.product p; "
                      "SELECT md5(string_agg(version||':'||coalesce(checksum::text,'')||':'||success::text,',' ORDER BY installed_rank)) FROM products.flyway_schema_history;").strip()

    baseline = durable_state()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    report["phases"].append({"name": "healthy-source-bound-digest", "passed": True})
    base = [args.docker, "compose", "-p", args.project, "--env-file", str(ROOT / ".env.example")]
    for name in ("docker-compose.yml", "docker-compose.e2e.yml", "docker-compose.lifecycle.yml",
                 "docker-compose.recovery-projector.yml", "docker-compose.operations-journey.yml"):
        base += ["-f", str(ROOT / name)]
    started = time.monotonic()
    try:
        with tempfile.TemporaryDirectory(prefix="omp-rollback-") as temporary:
            overlay = Path(temporary) / "rollback.json"
            def apply(broken, wait):
                service = {"image": image_id}
                if broken:
                    service["environment"] = {"SPRING_DATASOURCE_URL": "jdbc:postgresql://127.0.0.1:1/omp"}
                overlay.write_text(json.dumps({"services": {"product-service": service}}), encoding="utf-8")
                return subprocess.run([*base, "-f", str(overlay), "up", "-d", "--no-deps", "--no-build",
                                       "--pull", "never", "--wait", "--wait-timeout", str(wait), "product-service"],
                                      cwd=ROOT, capture_output=True, text=True, timeout=wait + 45)
            try:
                failed = apply(True, 30)
                # A parsing/ownership failure is not evidence of a real failed deployment.
                ids = docker("ps", "-aq", "--filter", "label=com.docker.compose.project=" + args.project,
                             "--filter", "label=com.docker.compose.service=product-service").split()
                if len(ids) != 1:
                    raise AssertionError("Failed replacement container is missing or ambiguous")
                candidate = json.loads(docker("inspect", ids[0]))[0]
                injected = "SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:1/omp" in candidate["Config"]["Env"]
                rejected = failed.returncode != 0 and injected and candidate["Id"] != original["Id"] and candidate["Image"] == image_id
                report["phases"].append({"name": "failed-replacement-rejected", "passed": rejected})
                if not rejected:
                    raise AssertionError("The deliberately invalid application replacement was not rejected")
            finally:
                restored = apply(False, maximum)
                if restored.returncode:
                    raise AssertionError("Accepted digest/configuration could not be restored within the recovery objective")
                current = inspect_container(args.docker, args.project, "product-service")
                item = json.loads(docker("inspect", current))[0]
                report["cleanupPassed"] = item["Image"] == image_id and item["State"]["Health"]["Status"] == "healthy"
                report["phases"].append({"name": "accepted-digest-restored", "passed": report["cleanupPassed"]})
        preserved = baseline == durable_state() and all(inspect_container(args.docker, args.project, name) == services[name]
                                                       for name in ("postgres", "inventory-service", "payment-service"))
        elapsed = time.monotonic() - started
        report["phases"].append({"name": "durable-state-and-stream-owners-preserved", "passed": preserved})
        report["recoverySeconds"] = round(elapsed, 2)
        report["maximumRtoSeconds"] = maximum
        report["passed"] = len(report["phases"]) == 4 and all(p["passed"] for p in report["phases"]) and elapsed <= maximum
    finally:
        if not report["passed"]:
            report["remediation"] = {"priority": "P1", "owner": "platform maintainers",
                                     "requiredAction": "Repair the retained application rollback failure and repeat the unchanged isolated drill before release."}
        args.output.write_text(json.dumps(report, indent=2), encoding="utf-8")
    if not report["passed"] or not report["cleanupPassed"]:
        raise AssertionError("Deployment rollback acceptance failed")
    print(json.dumps({"passed": True, "recoverySeconds": report["recoverySeconds"], "phases": report["phases"]}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True)
    parser.add_argument("--docker", default="docker")
    parser.add_argument("--output", type=Path, default=ROOT / "coverage/operations/deployment-rollback.json")
    execute(parser.parse_args())


if __name__ == "__main__":
    main()
