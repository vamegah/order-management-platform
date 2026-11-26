"""Encrypted full/WAL backup and isolated point-in-time restore using real application migrations."""
import argparse, hashlib, json, re, subprocess, time, uuid
from datetime import datetime, timezone
from pathlib import Path
import xml.etree.ElementTree as ET
from operations.evidence import evidence

ROOT = Path(__file__).resolve().parents[1]


class Recovery:
    def __init__(self, args):
        self.args = args
        if not re.fullmatch(r"omp-operations-[a-z0-9]{6,24}", args.project):
            raise ValueError("Fresh isolated operations project required")
        self.report = {"phases": [], "passed": False, "cleanupPassed": False}
        self.report.update(evidence(args.docker, ROOT))
        self.output = args.output
        self.output.mkdir(parents=True, exist_ok=True)

    def docker(self, *args, input=None, timeout=180):
        result = subprocess.run(
            [self.args.docker, *args],
            cwd=ROOT,
            input=None if input is None else input.encode("utf-8"),
            capture_output=True,
            timeout=timeout,
        )
        if result.returncode:
            raise RuntimeError(
                "Recovery command failed: "
                + " ".join(args[:3])
                + "\n"
                + (result.stderr + result.stdout).decode("utf-8", errors="replace")[
                    -3000:
                ]
            )
        return result.stdout.decode("utf-8", errors="replace")

    def compose(self, *args, input=None):
        return self.docker(
            "compose",
            "-p",
            self.args.project,
            "-f",
            "docker-compose.recovery-drill.yml",
            *args,
            input=input
        )

    def sql(self, service, sql):
        return self.compose(
            "exec",
            "-T",
            "--user",
            "postgres",
            service,
            "psql",
            "-XAt",
            "--set=ON_ERROR_STOP=1",
            "-U",
            "omp_admin",
            "-d",
            "omp",
            input=sql,
        ).strip()

    def wait(self, predicate, seconds=90):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            try:
                value = predicate()
                if value:
                    return value
            except (RuntimeError, ValueError):
                pass
            time.sleep(1)
        raise AssertionError("Recovery objective did not converge")

    def prepare(self):
        # Compose creates labelled volumes; initialize random credentials within a private named volume.
        self.compose("create", "postgres")
        volume = self.args.project + "_private"
        self.docker(
            "run",
            "--rm",
            "--user",
            "0",
            "--mount",
            "type=volume,source=" + volume + ",target=/private",
            "omp/postgres:section7-recovery",
            "sh",
            "-ec",
            "umask 077; for name in postgres_password pgbackrest_repo_cipher postgres_exporter_password; do head -c 32 /dev/urandom | base64 > /private/$name; chown postgres:2000 /private/$name; chmod 440 /private/$name; done",
        )
        self.compose("up", "-d", "postgres")
        self.wait(lambda: self.sql("postgres", "SELECT 1") == "1")
        self.compose(
            "exec",
            "-T",
            "--user",
            "root",
            "postgres",
            "chown",
            "postgres:postgres",
            "/var/lib/pgbackrest",
        )
        self.compose(
            "exec",
            "-T",
            "--user",
            "postgres",
            "postgres",
            "/usr/local/bin/omp-pgbackrest",
            "stanza-create",
        )
        roles = ";".join(
            "CREATE ROLE " + role
            for role in (
                "orders_svc",
                "payments_svc",
                "inventory_svc",
                "notifications_svc",
            )
        )
        self.sql(
            "postgres",
            roles
            + ";CREATE EXTENSION IF NOT EXISTS citext;CREATE EXTENSION IF NOT EXISTS pgcrypto;",
        )
        migrations = []
        for module, schema in [
            ("order-service", "orders"),
            ("payment-service", "payments"),
            ("inventory-service", "inventory"),
            ("notification-service", "notifications"),
        ]:
            self.sql("postgres", "CREATE SCHEMA " + schema)
            files = sorted(
                (ROOT / module / "src/main/resources/db/migration").glob("V*.sql"),
                key=lambda p: int(re.match(r"V(\d+)", p.name)[1]),
            )
            for source in files:
                if source.name == "V2__seed_data.sql":
                    continue
                self.sql(
                    "postgres",
                    "SET search_path TO "
                    + schema
                    + ",public;\n"
                    + source.read_text(encoding="utf-8-sig"),
                )
                migrations.append(
                    {
                        "path": source.relative_to(ROOT).as_posix(),
                        "sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
                    }
                )
        self.sql(
            "postgres",
            (ROOT / "infrastructure/postgres/monitoring.sql").read_text(
                encoding="utf-8"
            ),
        )
        assert (
            self.sql(
                "postgres",
                "SET ROLE omp_monitor; SELECT count(*) FROM operations.queue_backlog",
            ).splitlines()[-1]
            == "3"
        )
        try:
            self.sql(
                "postgres",
                "SET ROLE omp_monitor; SELECT count(*) FROM payments.payment",
            )
        except RuntimeError as denied:
            if "permission denied" not in str(denied):
                raise
        else:
            raise AssertionError("Monitoring role can read business payment payloads")
        self.report["monitoringRole"] = {
            "aggregateViewsAccepted": True,
            "businessTablesDenied": True,
        }
        self.report["migrations"] = migrations
        self.sql(
            "postgres", (ROOT / "scripts/operations/recovery-fixture.sql").read_text()
        )
        return {
            "migrations": len(migrations),
            "realApplicationSchemas": 4,
            "randomMountedCredentials": True,
        }

    def backup(self):
        self.compose(
            "exec",
            "-T",
            "--user",
            "postgres",
            "postgres",
            "/usr/local/bin/omp-pgbackrest",
            "--type=full",
            "backup",
        )
        self.compose(
            "exec",
            "-T",
            "--user",
            "postgres",
            "postgres",
            "/usr/local/bin/omp-pgbackrest",
            "check",
        )
        info = json.loads(
            self.compose(
                "exec",
                "-T",
                "--user",
                "postgres",
                "postgres",
                "/usr/local/bin/omp-pgbackrest",
                "--output=json",
                "info",
            )
        )
        self.report["backupLabel"] = info[0]["backup"][-1]["label"]
        assert info[0]["cipher"] == "aes-256-cbc"
        # This committed update exists only in WAL after the full backup.
        self.sql(
            "postgres",
            "INSERT INTO operations_recovery_marker VALUES (2,'committed-after-full-backup');",
        )
        self.target = self.sql("postgres", "SELECT clock_timestamp()")
        self.sql(
            "postgres",
            "SELECT pg_sleep(0.1);INSERT INTO operations_recovery_marker VALUES (3,'must-not-survive-target');SELECT pg_switch_wal();",
        )
        self.wait(
            lambda: self.sql(
                "postgres",
                "SELECT count(*) FROM pg_ls_dir('pg_wal/archive_status') f WHERE f LIKE '%.ready'",
            )
            == "0",
            60,
        )
        self.report["targetTime"] = self.target
        return {
            "cipher": "aes-256-cbc",
            "postBackupWalArchived": True,
            "targetTime": self.target,
        }

    def restore(self):
        start = time.monotonic()
        # A one-off container mounts only a new target directory; the source data volume is absent.
        self.compose("--profile", "restore", "create", "restored")
        self.compose(
            "--profile",
            "restore",
            "run",
            "--rm",
            "--no-deps",
            "--user",
            "root",
            "--entrypoint",
            "sh",
            "restored",
            "-ec",
            "chown postgres:postgres /restore/data",
        )
        script = (ROOT / "scripts/operations/postgres_restore.sh").read_text()
        target = self.target
        self.compose(
            "--profile",
            "restore",
            "run",
            "--rm",
            "--no-deps",
            "--user",
            "postgres",
            "--entrypoint",
            "sh",
            "restored",
            "-s",
            "--",
            "/restore/data",
            target,
            input=script,
        )
        self.compose("--profile", "restore", "up", "-d", "restored")
        self.wait(
            lambda: self.sql("restored", "SELECT pg_is_wal_replay_paused()") == "t", 120
        )
        assert (
            self.sql(
                "restored",
                "SELECT string_agg(id::text,',' ORDER BY id) FROM operations_recovery_marker",
            )
            == "1,2"
        )
        assert (
            self.sql(
                "restored",
                "SELECT count(*) FROM orders.order_line l JOIN inventory.reservation r ON r.id=l.reservation_id JOIN payments.payment p ON p.order_id=l.order_id WHERE r.sku=l.product_sku AND r.quantity=l.quantity",
            )
            == "1"
        )
        assert (
            self.sql(
                "restored",
                "SELECT count(*) FROM notifications.notification_dispatch WHERE state='PENDING'",
            )
            == "1"
        )
        assert (
            self.sql(
                "restored",
                "SELECT count(*) FROM orders.outbox_event WHERE status='FAILED'",
            )
            == "1"
        )
        elapsed = round(time.monotonic() - start, 2)
        assert elapsed <= 300
        return {
            "rtoSeconds": elapsed,
            "rpoSeconds": 0,
            "postBackupCommitPresent": True,
            "postTargetCommitAbsent": True,
            "referencesVerified": True,
            "pendingWorkPreserved": True,
            "pausedBeforeCutover": True,
        }

    def replay(self):
        self.compose(
            "exec",
            "-T",
            "--user",
            "postgres",
            "restored",
            "pg_ctl",
            "-D",
            "/restore/data",
            "promote",
        )
        self.wait(lambda: self.sql("restored", "SELECT pg_is_in_recovery()") == "f", 30)
        script = (ROOT / "scripts/outbox-replay.sql").read_text()
        self.compose(
            "exec",
            "-T",
            "--user",
            "postgres",
            "restored",
            "psql",
            "-X",
            "--set=ON_ERROR_STOP=1",
            "-U",
            "omp_admin",
            "-d",
            "omp",
            "--set=id=70000000-0000-0000-0000-000000000007",
            "--set=reason=isolated-restore-drill",
            "-c",
            "SET search_path TO orders,public",
            "-f",
            "-",
            input=script,
        )
        assert (
            self.sql(
                "restored",
                "SELECT status FROM orders.outbox_event WHERE id='70000000-0000-0000-0000-000000000007'",
            )
            == "PENDING"
        )
        assert (
            self.sql("restored", "SELECT count(*) FROM orders.outbox_recovery_audit")
            == "1"
        )
        assert (
            self.sql("postgres", "SELECT count(*) FROM orders.outbox_recovery_audit")
            == "0"
        )
        assert self.sql("restored", "SELECT count(*) FROM payments.payment") == "1"
        return {"auditedReplay": True, "sourceUnchanged": True, "externalMoneyCalls": 0}

    def save(self):
        (self.output / "postgres-recovery.json").write_text(
            json.dumps(self.report, indent=2), encoding="utf-8"
        )
        suite = ET.Element(
            "testsuite",
            name="postgres-recovery",
            tests=str(len(self.report["phases"])),
            failures=str(sum(not p["passed"] for p in self.report["phases"])),
            errors="0",
            skipped="0",
        )
        for phase in self.report["phases"]:
            case = ET.SubElement(
                suite,
                "testcase",
                classname="operations.recovery",
                name=phase["name"],
                time=str(phase["seconds"]),
            )
            if not phase["passed"]:
                ET.SubElement(case, "failure", message="Recovery objective failed")
        ET.ElementTree(suite).write(
            self.output / "postgres-recovery.xml",
            encoding="utf-8",
            xml_declaration=True,
        )

    def execute(self):
        existing = self.docker(
            "ps",
            "-aq",
            "--filter",
            "label=com.docker.compose.project=" + self.args.project,
        ).strip()
        if existing:
            raise ValueError("Recovery project already exists")
        try:
            for name, method in [
                ("application-schema-fixtures", self.prepare),
                ("encrypted-full-and-wal", self.backup),
                ("isolated-point-in-time-restore", self.restore),
                ("audited-outbox-replay", self.replay),
            ]:
                start = time.monotonic()
                try:
                    measurement = method()
                    passed = True
                except Exception:
                    self.report["phases"].append(
                        {
                            "name": name,
                            "passed": False,
                            "seconds": round(time.monotonic() - start, 2),
                        }
                    )
                    self.save()
                    raise
                self.report["phases"].append(
                    {
                        "name": name,
                        "passed": passed,
                        "seconds": round(time.monotonic() - start, 2),
                        "measurements": measurement,
                    }
                )
                self.save()
                print(json.dumps(self.report["phases"][-1]), flush=True)
            self.report["passed"] = len(self.report["phases"]) == 4
        finally:
            try:
                (self.output / "postgres-recovery-stack.log").write_text(
                    self.compose("logs", "--no-color", "--tail", "100"),
                    encoding="utf-8",
                )
            finally:
                self.compose(
                    "--profile", "restore", "down", "--volumes", "--remove-orphans"
                )
                self.report["cleanupPassed"] = True
                self.save()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True)
    parser.add_argument("--docker", default="docker")
    parser.add_argument("--output", type=Path, default=ROOT / "coverage/operations")
    Recovery(parser.parse_args()).execute()


if __name__ == "__main__":
    main()
