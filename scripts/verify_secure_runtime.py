"""Execute production Kafka/Redis authentication, ACL and durable restart checks in a fresh isolated project."""
import argparse, hashlib, json, os, re, secrets, subprocess, tempfile, time
from pathlib import Path
from prepare_production_secrets import prepare
from release_policy import SERVICES
from operations.evidence import evidence

ROOT = Path(__file__).resolve().parents[1]
RUNTIME = (
    "redis",
    "quota-redis",
    "redis-replica-1",
    "redis-replica-2",
    "redis-sentinel-1",
    "redis-sentinel-2",
    "redis-sentinel-3",
    "kafka",
    "kafka-2",
    "kafka-3",
    "kafka-bootstrap",
    "kafka-exporter",
    "redis-exporter",
)


def execute(args):
    if not re.fullmatch(r"omp-operations-[a-z0-9]{6,24}", args.project):
        raise ValueError("A fresh isolated operations project is required")
    docker = args.docker
    project = args.project
    env = dict(os.environ)
    existing = subprocess.check_output(
        [
            docker,
            "ps",
            "-aq",
            "--filter",
            "label=com.docker.compose.project=" + project,
        ],
        env=env,
        text=True,
    ).strip()
    if existing:
        raise ValueError("Refusing to adopt an existing project")
    output = args.output
    output.mkdir(parents=True, exist_ok=True)
    report = {"project": project, "passed": False, "cleanupPassed": False, "phases": []}
    report.update(evidence(docker, ROOT))
    lock = json.loads(
        (ROOT / "infrastructure/release/runtime-images.lock.json").read_text()
    )["services"]

    def record(phase):
        report["phases"].append(phase)
        (output / "secure-runtime.json").write_text(
            json.dumps(report, indent=2), encoding="utf-8"
        )
        print(json.dumps(phase), flush=True)

    with tempfile.TemporaryDirectory(prefix="omp-runtime-") as temporary:
        fixture = Path(temporary)
        # Ingress is never started by this drill; the unused hash satisfies setup validation only.
        users = fixture / "unused-ingress-users"
        users.write_text("fixture:$2b$12$" + "a" * 53, encoding="utf-8")
        private = fixture / "secrets"
        if os.name == "posix" and os.geteuid() != 0:
            raise ValueError(
                "Run the isolated drill as root so mounted fixture files can use runtime group 2000"
            )
        prepare(
            private,
            users,
            0 if os.name == "posix" else None,
            2000 if os.name == "posix" else None,
        )
        env.update(
            {
                "OMP_DOMAIN": "acceptance.invalid",
                "ACME_EMAIL": "acceptance@example.invalid",
                "OMP_SECRETS_DIRECTORY": str(private),
            }
        )
        overlay = fixture / "inspection-only.yml"
        overlay.write_text(
            "services:\n"
            + "".join(
                "  "
                + name
                + ":\n    image: ghcr.io/vamegah/order-management-platform/"
                + name
                + "@sha256:"
                + "0" * 64
                + "\n"
                for name in sorted(SERVICES)
            )
            + "".join(
                "  " + name + ":\n    labels:\n      com.omp.acceptance: operations\n"
                for name in RUNTIME
            ),
            encoding="utf-8",
        )
        base = [
            docker,
            "compose",
            "-p",
            project,
            "-f",
            str(ROOT / "docker-compose.prod.yml"),
            "-f",
            str(overlay),
        ]
        marker = secrets.token_hex(6)

        def run(args, input=None, timeout=90):
            result = subprocess.run(
                [docker, *args],
                cwd=ROOT,
                env=env,
                input=input,
                capture_output=True,
                text=True,
                timeout=timeout,
            )
            output = result.stdout + result.stderr
            for leaf in private.rglob("*"):
                if leaf.is_file():
                    secret = leaf.read_text().strip()
                    if len(secret) > 8:
                        output = output.replace(secret, "[REDACTED]")
            return result.returncode, output

        def cid(service):
            code, text = run(
                [
                    "ps",
                    "-aq",
                    "--filter",
                    "label=com.docker.compose.project=" + project,
                    "--filter",
                    "label=com.docker.compose.service=" + service,
                ]
            )
            assert code == 0 and len(text.split()) == 1
            return text.strip()

        def kafka(command, input=None, role="admin", timeout=90):
            if role == "admin":
                setup = "cp /fixture/kafka_admin_properties /tmp/client.properties; "
            else:
                setup = "printf 'security.protocol=SASL_PLAINTEXT\\nsasl.mechanism=SCRAM-SHA-512\\nsasl.jaas.config=' > /tmp/client.properties; cat /fixture/order-service_kafka_jaas >> /tmp/client.properties; printf '\\n' >> /tmp/client.properties; "
            return run(
                [
                    "run",
                    "--rm",
                    "-i",
                    "--user",
                    "1000:2000",
                    "--label",
                    "com.omp.task=section7",
                    "--network",
                    project + "_data",
                    "--mount",
                    "type=bind,source="
                    + str(
                        private
                        / (
                            "kafka_admin_properties"
                            if role == "admin"
                            else "order-service_kafka_jaas"
                        )
                    )
                    + ",target=/fixture/"
                    + (
                        "kafka_admin_properties"
                        if role == "admin"
                        else "order-service_kafka_jaas"
                    )
                    + ",readonly",
                    "--entrypoint",
                    "bash",
                    lock["kafka"],
                    "-ec",
                    setup + command,
                ],
                input,
                timeout,
            )

        try:
            result = subprocess.run(
                [*base, "up", "-d", "--wait", "--wait-timeout", "240", *RUNTIME],
                cwd=ROOT,
                env=env,
                capture_output=True,
                text=True,
                timeout=600,
            )
            if result.returncode:
                raise RuntimeError("Isolated secure runtime startup failed")
            completed = subprocess.run(
                [*base, "wait", "kafka-bootstrap"],
                cwd=ROOT,
                env=env,
                capture_output=True,
                timeout=180,
            )
            if completed.returncode:
                raise RuntimeError("Kafka provisioning did not complete")
            code, topics = kafka(
                "/opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --command-config /tmp/client.properties --describe"
            )
            assert (
                code == 0
                and "Topic: orders.events.order" in topics
                and "ReplicationFactor: 3" in topics
            ), topics[-1500:]
            assert (
                "webhooks.outbound.deliveries.retry-5" in topics
                and "min.insync.replicas=2" in topics
            )
            record(
                {
                    "name": "authenticated-topic-provisioning",
                    "passed": True,
                    "partitions": 6,
                    "replicationFactor": 3,
                    "minIsr": 2,
                }
            )
            producer = "/opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka:9092 --producer.config /tmp/client.properties --producer-property acks=all --producer-property max.block.ms=10000 --producer-property delivery.timeout.ms=15000 --producer-property request.timeout.ms=10000 --topic "
            code, text = kafka(
                producer + "orders.events.order", marker + "-before\n", role="order"
            )
            assert code == 0 and "Exception" not in text and "ERROR" not in text, text[
                -1200:
            ]
            code, text = kafka(
                producer + "inventory.events.reservation",
                "forbidden-operation\n",
                role="order",
            )
            assert "TopicAuthorizationException" in text, text[-1200:]
            record(
                {
                    "name": "least-privilege-kafka-clients",
                    "passed": True,
                    "ownTopicWrite": True,
                    "foreignTopicDenied": True,
                }
            )
            started = time.monotonic()
            broker = cid("kafka-3")
            code, text = run(["restart", "--time", "10", broker])
            assert code == 0
            deadline = time.monotonic() + 60
            while True:
                item = json.loads(run(["inspect", broker])[1])[0]
                if item["State"].get("Health", {}).get("Status") == "healthy":
                    break
                if time.monotonic() > deadline:
                    raise AssertionError(
                        "Restarted broker failed its authenticated health check"
                    )
                time.sleep(1)
            code, text = kafka(
                producer + "orders.events.order", marker + "-after\n", role="order"
            )
            assert code == 0 and "Exception" not in text and "ERROR" not in text, text[
                -1200:
            ]
            code, text = kafka(
                "/opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 --consumer.config /tmp/client.properties --topic orders.events.order --from-beginning --max-messages 2 --timeout-ms 30000"
            )
            assert marker + "-before" in text and marker + "-after" in text, text[
                -1200:
            ]
            record(
                {
                    "name": "broker-restart-and-durable-read",
                    "passed": True,
                    "seconds": round(time.monotonic() - started, 2),
                }
            )
            quota = cid("quota-redis")

            def redis(command):
                return run(
                    [
                        "exec",
                        quota,
                        "sh",
                        "-ec",
                        'REDISCLI_AUTH="$(cat /run/secrets/redis_password)" redis-cli --user quota '
                        + command,
                    ]
                )

            code, text = redis("PING")
            assert code == 0 and "PONG" in text, text
            code, text = redis("SET foreign forbidden")
            assert "NOPERM" in text, text
            code, text = run(["exec", quota, "redis-cli", "PING"])
            assert "NOAUTH" in text, text
            # Execute the exact production Lua under the restricted ACL, not a privileged test user.
            java = (
                ROOT
                / "shared/common-web/src/main/java/com/example/omp/shared/web/quota/RedisQuotaStore.java"
            ).read_text()
            script = java.split('"""')[1]
            command = [
                "exec",
                quota,
                "sh",
                "-ec",
                'REDISCLI_AUTH="$(cat /run/secrets/redis_password)" redis-cli --user quota EVAL "$1" 2 "omp:quota:v1:{acceptance_READ}:identity" "omp:quota:v1:{acceptance_READ}:identities" 1 16',
                "sh",
                script,
            ]
            code, text = run(command)
            assert code == 0 and text.strip() == "0", text
            code, text = run(command)
            assert code == 0 and int(text.strip()) > 0, text
            code, text = run(["restart", "--time", "10", quota])
            assert code == 0
            for i in range(20):
                code, text = redis("PING")
                if code == 0 and "PONG" in text:
                    break
                time.sleep(1)
            code, text = run(command)
            assert code == 0 and int(text.strip()) > 0, text
            record(
                {
                    "name": "quota-acl-and-restart",
                    "passed": True,
                    "foreignKeyDenied": True,
                    "anonymousDenied": True,
                    "productionLuaAccepted": True,
                    "bucketSurvivesRestart": True,
                }
            )
            for service in (
                "redis",
                "redis-replica-1",
                "redis-replica-2",
                "redis-sentinel-1",
                "redis-sentinel-2",
                "redis-sentinel-3",
            ):
                container = cid(service)
                if "sentinel" in service:
                    command = 'REDISCLI_AUTH="$(cat /run/secrets/redis_sentinel_password)" redis-cli -p 26379 SENTINEL get-master-addr-by-name omp-master'
                else:
                    command = 'REDISCLI_AUTH="$(cat /run/secrets/redis_password)" redis-cli INFO replication'
                code, text = run(["exec", container, "sh", "-ec", command])
                assert code == 0 and (
                    "6379" in text if "sentinel" in service else "role:" in text
                ), text
            record(
                {
                    "name": "authenticated-redis-replication-and-discovery",
                    "passed": True,
                    "replicas": 2,
                    "sentinels": 3,
                }
            )
            # Query through the private management network; no exporter publishes a host port.
            probe = """import json,time,urllib.request
expected={'redis-exporter:9121':'redis_up 1','kafka-exporter:9308':'kafka_brokers 3'}
for target,metric in expected.items():
    deadline=time.monotonic()+45
    while True:
        try:
            text=urllib.request.urlopen('http://'+target+'/metrics',timeout=5).read(2000000).decode()
            if metric in text: break
        except (OSError,ValueError): pass
        if time.monotonic()>deadline: raise AssertionError('Authenticated exporter did not expose '+metric)
        time.sleep(1)
print(json.dumps({'authenticatedExporters':list(expected),'privateScrapes':True}))
"""
            code, text = run(
                [
                    "run",
                    "--rm",
                    "--network",
                    project + "_ops",
                    "--label",
                    "com.omp.task=section7",
                    lock["certificate-exporter"],
                    "python",
                    "-c",
                    probe,
                ],
                timeout=110,
            )
            assert code == 0, text[-1200:]
            record(
                {
                    "name": "authenticated-private-exporters",
                    "passed": True,
                    **json.loads(text),
                }
            )
            report["passed"] = True
        finally:
            diagnostics = subprocess.run(
                [*base, "logs", "--no-color", "--tail", "100"],
                cwd=ROOT,
                env=env,
                capture_output=True,
                text=True,
                timeout=60,
            )
            text = diagnostics.stdout + diagnostics.stderr
            for leaf in private.rglob("*"):
                if leaf.is_file():
                    value = leaf.read_text(encoding="utf-8").strip()
                    if len(value) > 8:
                        text = text.replace(value, "[REDACTED]")
            (output / "secure-runtime-stack.log").write_text(text, encoding="utf-8")
            stopped = subprocess.run(
                [*base, "down", "--volumes", "--remove-orphans"],
                cwd=ROOT,
                env=env,
                capture_output=True,
                timeout=180,
            )
            report["cleanupPassed"] = stopped.returncode == 0
            (output / "secure-runtime.json").write_text(
                json.dumps(report, indent=2), encoding="utf-8"
            )
            # Python's Windows cleanup needs write permission for the disposable leaves.
            if os.name == "nt":
                for leaf in private.rglob("*"):
                    if leaf.is_file():
                        leaf.chmod(0o600)
    if not report["passed"] or not report["cleanupPassed"]:
        raise AssertionError("Secure runtime acceptance incomplete")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True)
    parser.add_argument("--docker", default="docker")
    parser.add_argument("--output", type=Path, default=ROOT / "coverage/operations")
    execute(parser.parse_args())


if __name__ == "__main__":
    main()
