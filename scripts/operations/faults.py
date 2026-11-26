"""Bounded Docker faults restricted to fresh, explicitly labelled acceptance containers."""
import argparse, json, re, subprocess, time
from pathlib import Path

FAULT_SERVICES = {
    "postgres": "postgres",
    "kafka": "kafka",
    "redis": "redis",
    "provider": "payment-provider-simulator",
    "soap": "legacy-bank-service",
    "downstream-http": "customer-service",
    "downstream-grpc": "inventory-service",
    "collector": "otel-collector",
}


def inspect_container(docker, project, service):
    if not re.fullmatch(r"omp-operations-[a-z0-9]{6,24}", project):
        raise ValueError("Isolated operations project required")
    result = subprocess.run(
        [
            docker,
            "ps",
            "-q",
            "--filter",
            "label=com.docker.compose.project=" + project,
            "--filter",
            "label=com.docker.compose.service=" + service,
        ],
        check=True,
        capture_output=True,
        text=True,
        timeout=20,
    )
    ids = result.stdout.split()
    if len(ids) != 1:
        raise ValueError(
            "Fault target must be exactly one isolated acceptance container"
        )
    result = subprocess.run(
        [docker, "inspect", ids[0]],
        check=True,
        capture_output=True,
        text=True,
        timeout=20,
    )
    item = json.loads(result.stdout)[0]
    labels = item["Config"].get("Labels", {})
    if (
        labels.get("com.omp.acceptance") != "operations"
        or labels.get("com.docker.compose.project") != project
    ):
        raise ValueError("Refusing a container without operations ownership labels")
    return ids[0]


def inject(docker, project, fault, seconds):
    if fault not in FAULT_SERVICES or not 1 <= seconds <= 30:
        raise ValueError("Unknown or unbounded fault")
    target = inspect_container(docker, project, FAULT_SERVICES[fault])
    started = time.monotonic()
    subprocess.run(
        [docker, "pause", target], check=True, capture_output=True, timeout=20
    )
    try:
        time.sleep(seconds)
    finally:
        subprocess.run(
            [docker, "unpause", target], check=True, capture_output=True, timeout=20
        )
    return {
        "fault": fault,
        "service": FAULT_SERVICES[fault],
        "seconds": round(time.monotonic() - started, 2),
        "restored": True,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--docker", default="docker")
    parser.add_argument("--project", required=True)
    parser.add_argument("--fault", choices=FAULT_SERVICES, required=True)
    parser.add_argument("--seconds", type=int, default=10)
    args = parser.parse_args()
    print(json.dumps(inject(args.docker, args.project, args.fault, args.seconds)))


if __name__ == "__main__":
    main()
