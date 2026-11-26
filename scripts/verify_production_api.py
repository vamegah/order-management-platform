"""Exercise real-issuer audience/metrics policies and shared quotas in two production-profile API containers."""
import argparse, json, os, re, secrets, subprocess, tempfile
from pathlib import Path
from prepare_production_secrets import prepare
from release_policy import SERVICES
from operations.evidence import evidence

ROOT = Path(__file__).resolve().parents[1]


def execute(args):
    project = args.project
    if not re.fullmatch(r"omp-operations-[a-z0-9]{6,24}", project):
        raise ValueError("Fresh isolated operations project required")
    env = dict(os.environ)
    sensitive = []

    def docker(*parts, input=None, timeout=180):
        result = subprocess.run(
            [args.docker, *parts],
            cwd=ROOT,
            env=env,
            input=input,
            capture_output=True,
            text=True,
            timeout=timeout,
        )
        if result.returncode:
            diagnostic = result.stderr[-4000:]
            for value in sensitive:
                diagnostic = diagnostic.replace(value, "[REDACTED]")
            (args.output / "production-api-stage-error.log").write_text(
                diagnostic, encoding="utf-8"
            )
            raise RuntimeError(
                "Acceptance Docker stage failed: "
                + parts[0]
                + "; see redacted stage diagnostics"
            )
        return result.stdout

    if docker(
        "ps", "-aq", "--filter", "label=com.docker.compose.project=" + project
    ).strip():
        raise ValueError("Refusing to adopt an existing project")
    output = args.output
    output.mkdir(parents=True, exist_ok=True)
    report = {
        "passed": False,
        "cleanupPassed": False,
        "phases": [],
        **evidence(args.docker, ROOT),
    }
    owned = {}
    for service, reference in (
        ("product-service", args.product_image),
        ("postgres", args.postgres_image),
    ):
        item = json.loads(docker("image", "inspect", reference))[0]
        labels = item["Config"].get("Labels") or {}
        if labels.get("org.opencontainers.image.revision") != report["sourceRevision"]:
            raise ValueError(
                "Acceptance image is not bound to the source revision: " + service
            )
        if labels.get("com.omp.source-tree") != report["sourceTreeSha256"]:
            raise ValueError(
                "Acceptance image does not match the source tree: " + service
            )
        owned[service] = item["Id"]
    report["ownedImages"] = owned

    def save():
        (output / "production-api.json").write_text(
            json.dumps(report, indent=2), encoding="utf-8"
        )

    with tempfile.TemporaryDirectory(prefix="omp-api-") as temporary:
        fixture = Path(temporary)
        users = fixture / "unused-ingress-users"
        users.write_text("fixture:$2b$12$" + "a" * 53, encoding="utf-8")
        private = fixture / "secrets"
        if os.name == "posix" and os.geteuid() != 0:
            raise ValueError(
                "Run the isolated drill as root to provision runtime group 2000"
            )
        prepare(
            private,
            users,
            0 if os.name == "posix" else None,
            2000 if os.name == "posix" else None,
        )
        credential = private / "acceptance_client_secret"
        with credential.open("x", encoding="utf-8") as stream:
            if os.name == "posix":
                os.fchmod(stream.fileno(), 0o400)
            stream.write(secrets.token_urlsafe(32))
        sensitive.extend(
            leaf.read_text(encoding="utf-8").strip()
            for leaf in private.rglob("*")
            if leaf.is_file() and len(leaf.read_text(encoding="utf-8").strip()) > 8
        )
        backup = fixture / "backup"
        backup.mkdir(mode=0o770)
        if os.name == "posix":
            os.chown(backup, 70, 2000)
            os.chmod(backup, 0o770)
        env.update(
            {
                "OMP_DOMAIN": "acceptance.invalid",
                "ACME_EMAIL": "acceptance@example.invalid",
                "OMP_SECRETS_DIRECTORY": str(private),
                "OMP_BACKUP_REPOSITORY": str(backup),
            }
        )
        overlay = fixture / "fixture-images.yml"
        text = "services:\n"
        for service in sorted(SERVICES):
            reference = owned.get(
                service,
                "ghcr.io/vamegah/order-management-platform/"
                + service
                + "@sha256:"
                + "0" * 64,
            )
            text += "  " + service + ":\n    image: " + reference + "\n"
            if service == "product-service":
                text += "    environment:\n      APP_QUOTA_MULTIPLIER: '0.02'\n    deploy:\n      replicas: 2\n    labels:\n      com.omp.acceptance: operations\n"
        overlay.write_text(text, encoding="utf-8")
        base = [
            args.docker,
            "compose",
            "-p",
            project,
            "-f",
            str(ROOT / "docker-compose.prod.yml"),
            "-f",
            str(overlay),
        ]
        try:
            boot = subprocess.run(
                [
                    *base,
                    "up",
                    "-d",
                    "--no-build",
                    "--wait",
                    "--wait-timeout",
                    "600",
                    "product-service",
                ],
                cwd=ROOT,
                env=env,
                capture_output=True,
                text=True,
                timeout=780,
            )
            if boot.returncode:
                raise RuntimeError("Production-profile API readiness failed")
            raw = docker(
                "compose",
                "-p",
                project,
                "-f",
                str(ROOT / "docker-compose.prod.yml"),
                "-f",
                str(overlay),
                "ps",
                "-q",
                "product-service",
            ).split()
            if len(raw) != 2:
                raise AssertionError("Exactly two API replicas must execute")
            ips = []
            for container in raw:
                item = json.loads(docker("inspect", container))[0]
                ips.append(
                    item["NetworkSettings"]["Networks"][project + "_data"]["IPAddress"]
                )
                for value in item["Config"].get("Env", []):
                    for leaf in private.rglob("*"):
                        if (
                            leaf.is_file()
                            and len(leaf.read_text()) > 8
                            and leaf.read_text() in value
                        ):
                            raise AssertionError(
                                "A mounted credential leaked into container metadata"
                            )
            probe_image = json.loads(
                (ROOT / "infrastructure/release/runtime-images.lock.json").read_text()
            )["services"]["certificate-exporter"]

            def probe(mode):
                command = [
                    "run",
                    "--rm",
                    "--label",
                    "com.omp.task=section7",
                    "--network",
                    project + "_data",
                    "--mount",
                    "type=bind,source=" + str(private) + ",target=/fixture,readonly",
                    "--mount",
                    "type=bind,source="
                    + str(ROOT / "scripts/operations/api_probe.py")
                    + ",target=/probe.py,readonly",
                    "-e",
                    "TARGET_IPS=" + ",".join(ips),
                    "-e",
                    "OMP_ACCEPTANCE_PROJECT=" + project,
                    "-e",
                    "OMP_ACCEPTANCE_FIXTURES=true",
                    probe_image,
                    "python",
                    "/probe.py",
                    mode,
                ]
                result = json.loads(docker(*command, timeout=120))
                report["phases"].append(result)
                save()
                print(json.dumps(result), flush=True)

            probe("baseline")
            quota = docker(
                "ps",
                "-q",
                "--filter",
                "label=com.docker.compose.project=" + project,
                "--filter",
                "label=com.docker.compose.service=quota-redis",
            ).strip()
            try:
                docker("pause", quota)
                probe("outage")
            finally:
                docker("unpause", quota)
            probe("recovered")
            report["passed"] = len(report["phases"]) == 3
        finally:
            logs = subprocess.run(
                [*base, "logs", "--no-color", "--tail", "150"],
                cwd=ROOT,
                env=env,
                capture_output=True,
                text=True,
                timeout=90,
            )
            text = logs.stdout + logs.stderr
            for leaf in private.rglob("*"):
                if leaf.is_file():
                    value = leaf.read_text(encoding="utf-8").strip()
                    if len(value) > 8:
                        text = text.replace(value, "[REDACTED]")
            (output / "production-api-stack.log").write_text(text, encoding="utf-8")
            stopped = subprocess.run(
                [*base, "down", "--volumes", "--remove-orphans"],
                cwd=ROOT,
                env=env,
                capture_output=True,
                timeout=180,
            )
            report["cleanupPassed"] = stopped.returncode == 0
            save()
            if os.name == "nt":
                for leaf in fixture.rglob("*"):
                    if leaf.is_file():
                        leaf.chmod(0o600)
    if not report["passed"] or not report["cleanupPassed"]:
        raise AssertionError("Production API acceptance incomplete")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True)
    parser.add_argument("--docker", default="docker")
    parser.add_argument(
        "--product-image", default="omp/product-service:operations-acceptance"
    )
    parser.add_argument("--postgres-image", default="omp/postgres:section7-recovery")
    parser.add_argument("--output", type=Path, default=ROOT / "coverage/operations")
    execute(parser.parse_args())


if __name__ == "__main__":
    main()
