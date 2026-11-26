"""Exercise real image scanning, keyless SBOM signing and tamper denial in verification CI.

This evidence is deliberately not a deployable release or a production signing identity.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

REPOSITORY = "vamegah/order-management-platform"


def run(*args, accepted=True):
    result = subprocess.run(args, capture_output=True, text=True, timeout=180)
    if (result.returncode == 0) != accepted:
        raise AssertionError("Cryptographic rehearsal command did not meet its expected outcome: " + args[1])
    return result.stdout


def execute(args):
    if os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("GITHUB_REPOSITORY") != REPOSITORY:
        raise ValueError("Keyless rehearsal requires this repository's actual GitHub workflow identity")
    revision, ref = os.environ["GITHUB_SHA"], os.environ["GITHUB_REF"]
    if not re.fullmatch(r"[0-9a-f]{40}", revision) or not re.fullmatch(r"refs/(?:pull/[0-9]+/merge|heads/(?:main|master))", ref):
        raise ValueError("Unexpected verification source")
    root = Path(__file__).resolve().parents[1]
    current = run("git", "rev-parse", "HEAD").strip()
    changes = run("git", "status", "--porcelain").strip()
    if current != revision or changes:
        raise ValueError("A clean exact-source checkout is required; HEAD=" + current + "; expected=" + revision + "; changed paths=" + changes[:1200])
    image = json.loads(run("docker", "image", "inspect", args.image))[0]
    if args.comparison:
        comparison = json.loads(run("docker", "image", "inspect", args.comparison))[0]
        if image["Id"] != comparison["Id"]:
            args.output.mkdir(parents=True, exist_ok=True)
            difference = {"passed": False, "images": []}
            for item in (image, comparison):
                files = run("docker", "run", "--rm", "--network", "none", "--read-only",
                            "--cap-drop", "ALL", "--user", "0", "--entrypoint", "find", item["Id"],
                            "/app", "/etc", "-type", "f", "-exec", "sha256sum", "{}", ";")
                values = {line.split(maxsplit=1)[1]: line.split(maxsplit=1)[0]
                          for line in files.splitlines() if len(line.split(maxsplit=1)) == 2
                          and line.split(maxsplit=1)[1] not in ("/etc/hostname", "/etc/hosts", "/etc/resolv.conf")}
                difference["images"].append({"id": item["Id"], "created": item["Created"],
                                             "layers": item["RootFS"]["Layers"], "fileHashes": values})
            first, second = [i["fileHashes"] for i in difference["images"]]
            difference["changedPaths"] = sorted(name for name in first.keys() | second.keys() if first.get(name) != second.get(name))
            (args.output / "reproduction-diff.json").write_text(json.dumps(difference, indent=2), encoding="utf-8")
            raise AssertionError("Independent deterministic image builds differ; exact config/layer/file diagnostics retained")
    output = args.output
    output.mkdir(parents=True, exist_ok=True)
    scan = json.loads((output / "scan.json").read_text(encoding="utf-8"))
    if scan.get("Metadata", {}).get("ImageID") != image["Id"]:
        raise AssertionError("Scan does not describe the tested image")
    findings = [v for result in scan.get("Results", []) for v in result.get("Vulnerabilities", []) if v.get("Severity") in ("HIGH", "CRITICAL")]
    if findings:
        raise AssertionError("High or critical image vulnerabilities block acceptance")
    sbom = output / "sbom.json"
    value = json.loads(sbom.read_text(encoding="utf-8"))
    if value.get("bomFormat") != "CycloneDX" or not value.get("components"):
        raise AssertionError("Image SBOM has no actual component inventory")
    identity = "https://github.com/" + REPOSITORY + "/.github/workflows/verify.yml@" + ref
    bundle = output / "sbom.sigstore.json"
    run("cosign", "sign-blob", "--yes", "--bundle", str(bundle), str(sbom))
    verification = ["cosign", "verify-blob", "--certificate-identity", identity,
                    "--certificate-oidc-issuer", "https://token.actions.githubusercontent.com",
                    "--certificate-github-workflow-sha", revision,
                    "--certificate-github-workflow-repository", REPOSITORY,
                    "--bundle", str(bundle)]
    run(*verification, str(sbom))
    tampered = output / "tampered-sbom.json"
    tampered.write_bytes(sbom.read_bytes() + b" ")
    run(*verification, str(tampered), accepted=False)
    tampered.unlink()
    foreign = verification.copy()
    foreign[foreign.index(identity)] = identity.replace(REPOSITORY, "foreign/untrusted")
    run(*foreign, str(sbom), accepted=False)
    report = {"passed": True, "scope": "non-deployable verification signing rehearsal",
              "sourceRevision": revision, "sourceDirty": False, "imageId": image["Id"],
              "reproducibleIndependentBuild": bool(args.comparison), "highCriticalFindings": 0,
              "sbomComponents": len(value["components"]), "verifiedWorkflowIdentity": identity,
              "tamperedPayloadRejected": True, "foreignIdentityRejected": True,
              "productionReleasePublished": False,
              "evidenceSha256": {name: hashlib.sha256((output / name).read_bytes()).hexdigest()
                                 for name in ("scan.json", "sbom.json", "sbom.sigstore.json")}}
    (output / "rehearsal.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", required=True)
    parser.add_argument("--comparison")
    parser.add_argument("--output", type=Path, required=True)
    execute(parser.parse_args())


if __name__ == "__main__":
    main()
