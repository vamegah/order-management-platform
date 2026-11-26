"""Resolve the standalone production manifest and enforce its reviewed runtime inventory.

Owned-image placeholders are used for inspection only. Promotion always verifies real signatures.
"""
import argparse, json, os, subprocess, tempfile
from pathlib import Path
from release_policy import SERVICES, PolicyError, validate_compose
from check_grpc_transport import check as check_grpc

ROOT = Path(__file__).resolve().parents[1]


def check_model(model, lock):
    expected = SERVICES | set(lock)
    if set(model.get("services", {})) != expected:
        raise PolicyError("Production service catalogue differs from reviewed release")
    validate_compose(model)
    for name, image in lock.items():
        if model["services"][name].get("image") != image:
            raise PolicyError(
                "Production image differs from reviewed digest lock: " + name
            )
    errors = check_grpc(model)
    if errors:
        raise PolicyError("; ".join(errors))
    for name, service in model["services"].items():
        for volume in service.get("volumes", []):
            if str(volume.get("source", "")).replace("\\", "/").endswith("docker.sock"):
                raise PolicyError(
                    "Production workloads cannot mount the Docker API socket"
                )
        if name in SERVICES - {"postgres", "legacy-bank-service"}:
            audience = service.get("environment", {}).get(
                "SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES", ""
            )
            if "omp-api" not in audience.split(","):
                raise PolicyError("Public JWT audience is not enforced: " + name)
    for name in SERVICES - {"postgres"}:
        if str(
            model["services"][name].get("environment", {}).get("TRACING_SAMPLE_RATE")
        ) not in ("1", "1.0"):
            raise PolicyError(
                "Tail sampling requires complete application head sampling: " + name
            )
    for name in ("payment-service", "notification-service"):
        if "egress" not in model["services"][name].get("networks", {}):
            raise PolicyError(
                "Provider and callback delivery require the private egress network: "
                + name
            )
    if not model.get("networks", {}).get("ops", {}).get("internal"):
        raise PolicyError("Management network must be private")
    for name in ("grafana", "loki", "tempo", "alloy", "certificate-exporter"):
        if set(model["services"][name].get("networks", {})) != {"ops"}:
            raise PolicyError(
                "Management-only service has business network access: " + name
            )
    if "data" in model["services"]["traefik"].get("networks", {}):
        raise PolicyError("Ingress cannot access the database network")
    if set(model["services"]["alertmanager"].get("networks", {})) != {"ops", "egress"}:
        raise PolicyError(
            "Alertmanager requires private management and receiver egress"
        )
    if "--providers.docker=false" not in model["services"]["traefik"]["command"]:
        raise PolicyError("Ingress must use the reviewed file provider")
    return {
        "services": len(expected),
        "lockedRuntimeImages": len(lock),
        "privateManagement": True,
        "rotatingMtls": True,
    }


def resolve(docker="docker"):
    lock = json.loads(
        (ROOT / "infrastructure/release/runtime-images.lock.json").read_text()
    )["services"]
    with tempfile.TemporaryDirectory(prefix="omp-inspect-") as temporary:
        overlay = Path(temporary) / "inspection-only.yml"
        overlay.write_text(
            "# Inspection only; these application images do not exist.\nservices:\n"
            + "".join(
                "  "
                + name
                + ":\n    image: ghcr.io/vamegah/order-management-platform/"
                + name
                + "@sha256:"
                + "0" * 64
                + "\n"
                for name in sorted(SERVICES)
            ),
            encoding="utf-8",
        )
        env = {
            **os.environ,
            "OMP_DOMAIN": "acceptance.example.invalid",
            "ACME_EMAIL": "acceptance@example.invalid",
        }
        command = [
            docker,
            "compose",
            "-f",
            str(ROOT / "docker-compose.prod.yml"),
            "-f",
            str(overlay),
            "config",
            "--format",
            "json",
        ]
        result = subprocess.run(
            command,
            cwd=ROOT,
            env=env,
            capture_output=True,
            text=True,
            check=True,
            timeout=60,
        )
        return check_model(json.loads(result.stdout), lock)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--docker", default="docker")
    args = parser.parse_args()
    print(json.dumps(resolve(args.docker)))


if __name__ == "__main__":
    main()
