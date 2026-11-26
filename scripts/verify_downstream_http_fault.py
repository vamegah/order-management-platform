"""Measure HTTP dependency failure and recovery only in a labelled disposable operations stack."""
import argparse
import json
from pathlib import Path
import subprocess
import time
from urllib.request import Request, build_opener, ProxyHandler, HTTPRedirectHandler
from urllib.parse import urlencode
from operations.evidence import evidence
from operations.faults import inspect_container

ROOT = Path(__file__).resolve().parents[1]


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def execute(args):
    target = inspect_container(args.docker, args.project, "customer-service")
    ids = subprocess.check_output([args.docker, "ps", "-q", "--filter", "label=com.docker.compose.project=" + args.project], text=True).split()
    services = {}
    for item in json.loads(subprocess.check_output([args.docker, "inspect", *ids], text=True)):
        if item["Config"].get("Labels", {}).get("com.omp.acceptance") != "operations":
            raise ValueError("Refusing an unowned dependency fixture")
        services[item["Config"]["Labels"]["com.docker.compose.service"]] = item

    def origin(name, port):
        binding = services[name]["NetworkSettings"]["Ports"][str(port) + "/tcp"][0]
        if binding["HostIp"] != "127.0.0.1":
            raise ValueError("Only loopback acceptance endpoints are supported")
        return "http://127.0.0.1:" + binding["HostPort"]

    opener = build_opener(ProxyHandler({}), NoRedirect())

    def call(url, host, body, token=None, form=False):
        headers = {"Host": host, "Content-Type": "application/x-www-form-urlencoded" if form else "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        raw = urlencode(body).encode() if form else json.dumps(body).encode()
        with opener.open(Request(url, data=raw, headers=headers), timeout=8) as response:
            if response.status != 200:
                raise AssertionError("Unexpected dependency-probe response")
            value = response.read(262145)
            if len(value) > 262144:
                raise ValueError("Dependency-probe response exceeded its bound")
            return json.loads(value)

    realm = json.loads((ROOT / "infrastructure/keycloak/import/omp-realm.json").read_text(encoding="utf-8"))
    alice = next(user for user in realm["users"] if user["username"] == "alice")
    token = call(origin("keycloak", 8080) + "/realms/omp/protocol/openid-connect/token", "keycloak.localhost",
                 {"grant_type": "password", "client_id": "postman", "username": "alice",
                  "password": next(c["value"] for c in alice["credentials"] if c["type"] == "password")}, form=True)["access_token"]
    query = {"query": '{ customer(id: "00000000-0000-0000-0000-000000000101") { id } }'}
    def read():
        return call(origin("traefik", 80) + "/graphql", "graphql.localhost", query, token)
    def healthy(value):
        return not value.get("errors") and value.get("data", {}).get("customer", {}).get("id") == "00000000-0000-0000-0000-000000000101"

    objective = json.loads((ROOT / "docs/operations-slos.json").read_text(encoding="utf-8"))["recovery"]
    report = {"passed": False, "cleanupPassed": False, "phases": [], **evidence(args.docker, ROOT)}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    try:
        assert healthy(read()), "Healthy dependency baseline failed"
        report["phases"].append({"name": "healthy-http-baseline", "passed": True})
        try:
            subprocess.run([args.docker, "pause", target], check=True, capture_output=True, timeout=20)
            started = time.monotonic()
            value = read()
            elapsed = (time.monotonic() - started) * 1000
            codes = [error.get("extensions", {}).get("code") for error in value.get("errors", [])]
            passed = codes == ["DOWNSTREAM_SERVICE_ERROR"] and elapsed <= objective["maximumDownstreamHttpFailureMs"]
            report["phases"].append({"name": "paused-http-dependency", "passed": passed, "milliseconds": round(elapsed, 2), "codes": codes})
        finally:
            subprocess.run([args.docker, "unpause", target], check=True, capture_output=True, timeout=20)
        started = time.monotonic()
        deadline = started + objective["maximumDownstreamHttpRecoverySeconds"]
        while not healthy(read()):
            if time.monotonic() > deadline:
                raise AssertionError("HTTP dependency did not recover")
            time.sleep(.25)
        report["cleanupPassed"] = True
        recovery_ms = (time.monotonic() - started) * 1000
        report["phases"].append({"name": "restored-http-dependency", "passed": recovery_ms <= objective["maximumDownstreamHttpRecoverySeconds"] * 1000, "milliseconds": round(recovery_ms, 2)})
        report["passed"] = all(p["passed"] for p in report["phases"]) and len(report["phases"]) == 3
    finally:
        if not report["passed"]:
            report["remediation"] = {"priority": "P1", "owner": "platform maintainers", "requiredAction": "Fix the retained HTTP failure classification or deadline; repeat the unchanged profile before release."}
        args.output.write_text(json.dumps(report, indent=2), encoding="utf-8")
    assert report["passed"] and report["cleanupPassed"], "HTTP dependency acceptance failed"
    print(json.dumps({"passed": True, "phases": report["phases"]}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True)
    parser.add_argument("--docker", default="docker")
    parser.add_argument("--output", type=Path, default=ROOT / "coverage/operations/downstream-http.json")
    execute(parser.parse_args())


if __name__ == "__main__":
    main()
