"""Run release vulnerability scans without inherited ignore policies or configuration."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
from release_policy import DIGEST, PolicyError


LOCAL_IMAGE = re.compile(r"sha256:[0-9a-f]{64}")


def scan_image(reference, output, trivy="trivy"):
    if not (DIGEST.fullmatch(reference) or LOCAL_IMAGE.fullmatch(reference)):
        raise PolicyError("Scan requires an immutable image digest or local image ID")
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    # Trivy settings can otherwise suppress vulnerabilities even with a strict exit code.
    environment = {k: v for k, v in os.environ.items() if not k.startswith("TRIVY_")}
    try:
        result = subprocess.run(
            [trivy, "image", "--config", "", "--ignorefile", "", "--ignore-policy", "",
             "--ignore-unfixed=false", "--scanners", "vuln", "--severity", "HIGH,CRITICAL",
             "--exit-code", "1", "--format", "json", "--output", str(output), reference],
            env=environment, capture_output=True, timeout=600,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        raise PolicyError("Release vulnerability scanner did not complete") from error
    if result.returncode:
        raise PolicyError("Image failed the current high/critical vulnerability policy")
    try:
        report = json.loads(output.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        raise PolicyError("Release vulnerability scan evidence is missing or invalid") from error
    if (report.get("SchemaVersion") != 2 or report.get("ArtifactName") != reference
            or not isinstance(report.get("Metadata"), dict) or "Results" not in report
            or report["Results"] is not None and not isinstance(report["Results"], list)):
        raise PolicyError("Release vulnerability scan is incomplete or describes another image")
    if any(v.get("Severity") in ("HIGH", "CRITICAL")
           for item in report["Results"] or [] for v in item.get("Vulnerabilities") or []):
        raise PolicyError("Image failed the current high/critical vulnerability policy")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("image")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    scan_image(args.image, args.output)
    print(json.dumps({"scanPassed": True, "image": args.image, "highCriticalFindings": 0}))


if __name__ == "__main__":
    main()
