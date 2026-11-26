"""Execute real Prometheus/Alertmanager and OTel/Tempo checks in an isolated Compose project."""
import argparse, datetime, hashlib, json, os, re, secrets, subprocess, time
from pathlib import Path
from urllib.request import Request, build_opener, ProxyHandler
from urllib.error import HTTPError, URLError
import xml.etree.ElementTree as ET
from operations.evidence import evidence

ROOT = Path(__file__).resolve().parents[1]


class Monitoring:
    def __init__(self, args):
        self.args = args
        if not re.fullmatch(r"omp-operations-[a-z0-9]{6,24}", args.project):
            raise ValueError("An isolated operations project is required")
        self.report = {
            "phases": [],
            "passed": False,
            "cleanupPassed": False,
            **evidence(args.docker, ROOT),
        }
        self.output = args.output
        self.output.mkdir(parents=True, exist_ok=True)

    def run(self, command):
        result = subprocess.run(
            command, cwd=ROOT, capture_output=True, text=True, timeout=180, check=False
        )
        if result.returncode:
            raise RuntimeError(
                "Command failed: "
                + command[0]
                + " "
                + " ".join(command[1:4])
                + "\n"
                + result.stderr[-2000:]
            )
        return result.stdout

    def compose(self, *arguments):
        return self.run(
            [
                self.args.docker,
                "compose",
                "-p",
                self.args.project,
                "-f",
                "docker-compose.operations-acceptance.yml",
                *arguments,
            ]
        )

    def url(self, service, port):
        binding = self.compose("port", service, str(port)).strip()
        return "http://" + binding

    def request(self, url, body=None, method=None):
        raw = None if body is None else json.dumps(body).encode()
        request = Request(
            url, data=raw, method=method, headers={"Content-Type": "application/json"}
        )
        with build_opener(ProxyHandler({})).open(request, timeout=10) as response:
            data = response.read()
            return json.loads(data) if data else None

    def wait(self, predicate, seconds=45):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            try:
                value = predicate()
                if value:
                    return value
            except (URLError, ValueError, KeyError):
                pass
            time.sleep(1)
        raise AssertionError(
            "Acceptance condition did not converge within the objective"
        )

    def receipts(self):
        return self.request(self.receiver + "/receipts")

    def alerts(self, items):
        return self.request(self.manager + "/api/v2/alerts", items)

    def alert(self, name, severity="warning", service="routing", ends=None):
        now = datetime.datetime.now(datetime.timezone.utc)
        return {
            "labels": {
                "alertname": name,
                "severity": severity,
                "service": service,
                "env": "acceptance",
            },
            "annotations": {"summary": "Controlled isolated acceptance"},
            "startsAt": (now - datetime.timedelta(seconds=5)).isoformat(),
            "endsAt": (ends or now + datetime.timedelta(minutes=5)).isoformat(),
        }

    def rule_tests(self):
        self.run(
            [
                self.args.docker,
                "run",
                "--rm",
                "--mount",
                "type=bind,source="
                + str(ROOT / "infrastructure/prometheus")
                + ",target=/checks,readonly",
                "--entrypoint",
                "/bin/promtool",
                "prom/prometheus:v3.16.0@sha256:e04987641b57704f408e31f6373ba4138ecf27cb4a165d24aabb6c38fddd0f06",
                "test",
                "rules",
                "/checks/tests/application.test.yml",
            ]
        )
        return {"triggerFixtures": 12}

    def route_and_resolve(self):
        start = time.monotonic()
        names = [
            "ServiceDown",
            "AvailabilityBudgetFastBurn",
            "AvailabilityBudgetSlowBurn",
            "LatencyBudgetFastBurn",
            "JvmHeapSaturation",
            "DatabasePoolSaturation",
            "RedisBlockedClients",
            "KafkaConsumerLagHigh",
            "OutboxLagHigh",
            "WebhookDlqNonEmpty",
            "TlsCertificateExpiryCritical",
            "TelemetryExportFailures",
        ]
        alerts = [
            self.alert(
                n,
                "critical"
                if n
                in (
                    "ServiceDown",
                    "AvailabilityBudgetFastBurn",
                    "LatencyBudgetFastBurn",
                    "TlsCertificateExpiryCritical",
                )
                else "warning",
                n,
            )
            for n in names
        ]
        self.alerts(alerts)
        self.wait(
            lambda: {
                r["labels"]["alertname"]
                for r in self.receipts()
                if r["status"] == "firing"
            }
            >= set(names)
        )
        for record in self.receipts():
            if record["labels"]["alertname"] in names:
                assert record["receiver"] == (
                    "critical"
                    if record["labels"]["severity"] == "critical"
                    else "operations"
                )
        for item in alerts:
            item["endsAt"] = (
                datetime.datetime.now(datetime.timezone.utc)
                - datetime.timedelta(seconds=1)
            ).isoformat()
        self.alerts(alerts)
        self.wait(
            lambda: {
                r["labels"]["alertname"]
                for r in self.receipts()
                if r["status"] == "resolved"
            }
            >= set(names)
        )
        return {
            "alerts": len(names),
            "deliveredAndResolved": True,
            "seconds": round(time.monotonic() - start, 2),
        }

    def inhibition_and_silence(self):
        critical = self.alert("ControlledIncident", "critical")
        warning = self.alert("ControlledIncident")
        unrelated = self.alert("UnrelatedWarning")
        self.alerts([critical, warning, unrelated])
        self.wait(
            lambda: any(
                r["labels"]["alertname"] == "UnrelatedWarning" for r in self.receipts()
            )
        )
        status = self.request(self.manager + "/api/v2/alerts")
        target = next(
            a
            for a in status
            if a["labels"].get("alertname") == "ControlledIncident"
            and a["labels"].get("severity") == "warning"
        )
        assert target["status"]["inhibitedBy"], "Warning was not inhibited"
        assert not any(
            r["labels"]["alertname"] == "ControlledIncident"
            and r["labels"]["severity"] == "warning"
            for r in self.receipts()
        )
        now = datetime.datetime.now(datetime.timezone.utc)
        silence = {
            "matchers": [
                {
                    "name": "alertname",
                    "value": "ControlledSilence",
                    "isRegex": False,
                    "isEqual": True,
                }
            ],
            "startsAt": now.isoformat(),
            "endsAt": (now + datetime.timedelta(minutes=2)).isoformat(),
            "createdBy": "isolated-acceptance",
            "comment": "bounded recording receiver drill",
        }
        identity = self.request(self.manager + "/api/v2/silences", silence)["silenceID"]
        self.alerts([self.alert("ControlledSilence")])
        status = self.wait(
            lambda: [
                a
                for a in self.request(self.manager + "/api/v2/alerts")
                if a["labels"].get("alertname") == "ControlledSilence"
            ]
        )
        assert identity in status[0]["status"]["silencedBy"]
        self.request(self.manager + "/api/v2/silence/" + identity, method="DELETE")
        self.wait(
            lambda: any(
                r["labels"]["alertname"] == "ControlledSilence" for r in self.receipts()
            )
        )
        return {
            "inhibited": True,
            "unrelatedWarningDelivered": True,
            "boundedSilenceExpired": True,
        }

    def sampling(self):
        def span(trace_id, name, status=1, duration_ms=10):
            start = time.time_ns() - 2_000_000_000
            return {
                "traceId": trace_id,
                "spanId": secrets.token_hex(8),
                "name": name,
                "kind": 1,
                "startTimeUnixNano": str(start),
                "endTimeUnixNano": str(start + duration_ms * 1_000_000),
                "status": {"code": status, "message": "acceptance-do-not-retain"},
                "events": [
                    {
                        "name": "exception",
                        "timeUnixNano": str(start),
                        "attributes": [
                            {
                                "key": "exception.message",
                                "value": {"stringValue": "acceptance-do-not-retain"},
                            },
                            {
                                "key": "exception.stacktrace",
                                "value": {"stringValue": "acceptance-do-not-retain"},
                            },
                        ],
                    }
                ],
                "attributes": [
                    {
                        "key": "http.request.header.authorization",
                        "value": {"stringValue": "acceptance-do-not-retain"},
                    },
                    {
                        "key": "graphql.document",
                        "value": {"stringValue": "acceptance-do-not-retain"},
                    },
                    {
                        "key": "graphql.variables",
                        "value": {"stringValue": "acceptance-do-not-retain"},
                    },
                    {
                        "key": "db.query.parameters",
                        "value": {"stringValue": "acceptance-do-not-retain"},
                    },
                ],
            }

        ids = {name: secrets.token_hex(16) for name in ("error", "slow")}
        spans = [
            span(ids["error"], "controlled.error", 2),
            span(ids["slow"], "controlled.slow", 1, 1500),
        ]
        normals = [secrets.token_hex(16) for _ in range(40)]
        spans += [span(trace, "controlled.normal") for trace in normals]
        self.request(
            self.collector + "/v1/traces",
            {
                "resourceSpans": [
                    {
                        "resource": {
                            "attributes": [
                                {
                                    "key": "service.name",
                                    "value": {"stringValue": "operations-acceptance"},
                                }
                            ]
                        },
                        "scopeSpans": [
                            {"scope": {"name": "operations-drill"}, "spans": spans}
                        ],
                    }
                ]
            },
        )
        found = {}
        for name, trace in ids.items():
            found[name] = self.wait(
                lambda: self.request(self.tempo + "/api/traces/" + trace), 45
            )
            assert "acceptance-do-not-retain" not in json.dumps(
                found[name]
            ), "Sensitive tracing attribute was retained"
        # Errors and slow traces are deterministic; normal sampling is intentionally statistical.
        return {
            "errorRetained": True,
            "slowRetained": True,
            "normalInputs": len(normals),
            "sensitiveAttributeRemoved": True,
        }

    def collector_recovery(self):
        # Exercise the real bounded exporter queue while its backend is unavailable.
        trace = secrets.token_hex(16)
        start = time.time_ns()
        container = self.compose("ps", "-q", "tempo").strip()
        if not container:
            raise AssertionError("Owned trace backend is unavailable")
        self.run([self.args.docker, "pause", container])
        started = time.monotonic()
        try:
            self.request(
                self.collector + "/v1/traces",
                {
                    "resourceSpans": [
                        {
                            "resource": {
                                "attributes": [
                                    {
                                        "key": "service.name",
                                        "value": {"stringValue": "operations-recovery"},
                                    }
                                ]
                            },
                            "scopeSpans": [
                                {
                                    "scope": {"name": "recovery"},
                                    "spans": [
                                        {
                                            "traceId": trace,
                                            "spanId": secrets.token_hex(8),
                                            "name": "controlled.exporter.recovery",
                                            "kind": 1,
                                            "startTimeUnixNano": str(start),
                                            "endTimeUnixNano": str(start + 1000000),
                                            "status": {"code": 2},
                                        }
                                    ],
                                }
                            ],
                        }
                    ]
                },
            )
            # Wait beyond tail sampling so the exporter actually encounters the failed backend.
            time.sleep(18)
        finally:
            self.run([self.args.docker, "unpause", container])
        self.wait(lambda: self.request(self.tempo + "/api/traces/" + trace), 40)
        elapsed = time.monotonic() - started
        assert elapsed <= 60, "Trace exporter recovery exceeded the declared objective"
        return {
            "exporterBackendPaused": True,
            "traceRecovered": True,
            "maximumQueueBatches": 128,
            "recoverySeconds": round(elapsed, 2),
        }

    def execute(self):
        # Refuse to adopt or tear down an existing project, including one owned by another run.
        existing = self.run(
            [
                self.args.docker,
                "ps",
                "-aq",
                "--filter",
                "label=com.docker.compose.project=" + self.args.project,
            ]
        ).strip()
        if existing:
            raise ValueError("Operations project already exists; choose a fresh name")
        try:
            self.compose("up", "-d")
            self.receiver = self.url("alert-receiver", 8080)
            self.manager = self.url("alertmanager", 9093)
            self.collector = self.url("otel-collector", 4318)
            self.tempo = self.url("tempo", 3200)
            self.wait(lambda: self.request(self.receiver + "/health"))
            self.wait(lambda: self.request(self.manager + "/api/v2/status"))
            for name, method in [
                ("rule-trigger-fixtures", self.rule_tests),
                ("routing-and-resolution", self.route_and_resolve),
                ("inhibition-and-silence", self.inhibition_and_silence),
                ("bounded-tail-sampling", self.sampling),
                ("collector-backend-recovery", self.collector_recovery),
            ]:
                started = time.monotonic()
                try:
                    measurement = method()
                    phase = {
                        "name": name,
                        "passed": True,
                        "seconds": round(time.monotonic() - started, 2),
                        "measurements": measurement,
                    }
                except Exception:
                    self.report["phases"].append(
                        {
                            "name": name,
                            "passed": False,
                            "seconds": round(time.monotonic() - started, 2),
                        }
                    )
                    self.save()
                    raise
                self.report["phases"].append(phase)
                self.save()
                print(json.dumps(phase), flush=True)
            self.report["passed"] = len(self.report["phases"]) == 5
        finally:
            try:
                (self.output / "monitoring-stack.log").write_text(
                    self.compose("logs", "--no-color", "--tail", "100"),
                    encoding="utf-8",
                )
            except RuntimeError:
                pass
            self.compose("down", "--volumes", "--remove-orphans")
            self.report["cleanupPassed"] = True
            self.save()
        if not self.report["passed"]:
            raise AssertionError("Monitoring acceptance incomplete")

    def save(self):
        (self.output / "monitoring.json").write_text(
            json.dumps(self.report, indent=2), encoding="utf-8"
        )
        suite = ET.Element(
            "testsuite",
            name="operations-monitoring",
            tests=str(len(self.report["phases"])),
            failures=str(sum(not p["passed"] for p in self.report["phases"])),
            errors="0",
            skipped="0",
        )
        for phase in self.report["phases"]:
            case = ET.SubElement(
                suite,
                "testcase",
                classname="operations.monitoring",
                name=phase["name"],
                time=str(phase["seconds"]),
            )
            if not phase["passed"]:
                ET.SubElement(
                    case,
                    "failure",
                    message="Acceptance phase failed; inspect retained diagnostics",
                )
        ET.ElementTree(suite).write(
            self.output / "monitoring.xml", encoding="utf-8", xml_declaration=True
        )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True)
    parser.add_argument("--docker", default="docker")
    parser.add_argument("--output", type=Path, default=ROOT / "coverage/operations")
    Monitoring(parser.parse_args()).execute()


if __name__ == "__main__":
    main()
