"""Fail when an executable API surface changes without an explicit reviewed inventory update."""
import argparse, hashlib, json
from pathlib import Path

TOKENS = (
    "@RestController",
    "@QueryMapping",
    "@MutationMapping",
    "@SchemaMapping",
    "@GrpcService",
    "@Endpoint",
    "@EnableWebSocketMessageBroker",
)


def check(root):
    review = json.loads(
        (root / "docs/security/api-review.json").read_text(encoding="utf-8-sig")
    )
    actual = {}
    for module in root.iterdir():
        main = module / "src/main/java"
        if not main.is_dir():
            continue
        for source in main.rglob("*.java"):
            if any(token in source.read_text(encoding="utf-8-sig") for token in TOKENS):
                actual[source.relative_to(root).as_posix()] = hashlib.sha256(
                    source.read_bytes()
                ).hexdigest()
    declared = {item["source"]: item for item in review["surfaces"]}
    if actual.keys() != declared.keys():
        raise ValueError(
            "Executable API inventory changed; review missing and removed surfaces"
        )
    for source, digest in actual.items():
        item = declared[source]
        if digest != item["sha256"]:
            raise ValueError("API surface changed; repeat review: " + source)
        if not item.get("controls") or set(item.get("categories", [])) != {
            "API" + str(i) + ":2023" for i in range(1, 11)
        }:
            raise ValueError("Incomplete OWASP review: " + source)
        if not item.get("negativeTests") or not all(
            (root / test).is_file() for test in item["negativeTests"]
        ):
            raise ValueError("Missing remediation regression tests: " + source)
    findings = review.get("releaseBlockingFindings", [])
    if len({finding.get("id") for finding in findings}) != len(findings):
        raise ValueError("Duplicate review finding identity")
    for finding in findings:
        if (
            not finding.get("remediation")
            or not finding.get("tests")
            or not all((root / test).is_file() for test in finding["tests"])
        ):
            raise ValueError(
                "Review finding lacks a concrete remediation and executable evidence"
            )
    if review.get("residualRisks"):
        raise ValueError(
            "Residual risks require a named maintainer disposition before release"
        )
    return {"reviewedSurfaces": len(actual), "categories": 10}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path("."))
    args = parser.parse_args()
    print(json.dumps(check(args.root.resolve())))


if __name__ == "__main__":
    main()
