"""Create source-bound release metadata from scanned immutable images."""
import argparse, hashlib, json, os, re, subprocess
from pathlib import Path
from datetime import datetime, timezone
from release_policy import SERVICES, DIGEST, TAG, validate_bundle
from release_scanning import scan_image

ROOT = Path(__file__).resolve().parents[1]


def write(path, data):
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def image(service, reference, output):
    if service not in SERVICES or not DIGEST.fullmatch(reference):
        raise ValueError("Invalid release image")
    revision = os.environ["GITHUB_SHA"]
    if not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise ValueError("Invalid source")
    repo = os.environ["GITHUB_REPOSITORY"]
    run = os.environ["GITHUB_RUN_ID"]
    predicate = {
        "buildDefinition": {
            "buildType": "https://github.com/docker/build-push-action@v6",
            "externalParameters": {
                "repository": "https://github.com/" + repo,
                "sourceRevision": revision,
                "service": service,
                "platform": "linux/amd64",
            },
            "resolvedDependencies": [
                {
                    "uri": "git+https://github.com/" + repo,
                    "digest": {"gitCommit": revision},
                }
            ],
        },
        "runDetails": {
            "builder": {
                "id": "https://github.com/" + repo + "/.github/workflows/release.yml"
            },
            "metadata": {
                "invocationId": "https://github.com/" + repo + "/actions/runs/" + run
            },
        },
        "sourceRevision": revision,
        "image": reference,
    }
    write(output / (service + "-provenance.json"), predicate)
    record = {"service": service, "sourceRevision": revision, "image": reference}
    if service == "postgres":
        inputs = {
            p.relative_to(ROOT).as_posix(): sha(p)
            for p in sorted((ROOT / "infrastructure/postgres").rglob("*"))
            if p.is_file()
        }
        record["runtimeSourceSha256"] = hashlib.sha256(
            json.dumps(inputs, sort_keys=True).encode()
        ).hexdigest()
    for kind in ("sbom", "scan", "provenance"):
        source = output / (service + "-" + kind + ".json")
        record[kind] = {"file": source.name, "sha256": sha(source)}
    write(output / (service + "-image.json"), record)


def migration_compatibility():
    review = json.loads(
        (ROOT / "infrastructure/release/migration-review.json").read_text()
    )
    baseline = review.get("baselineRevision", "")
    if review.get("schemaVersion") != 1 or not re.fullmatch(r"[0-9a-f]{40}", baseline):
        raise ValueError("Invalid reviewed migration baseline")
    historical = subprocess.check_output(
        [
            "git",
            "-c",
            "safe.directory=" + ROOT.as_posix(),
            "ls-tree",
            "-r",
            "--name-only",
            baseline,
        ],
        cwd=ROOT,
        text=True,
    ).splitlines()
    historical = [
        p
        for p in historical
        if re.fullmatch(r"[a-z-]+/src/main/resources/db/migration/V[^/]+\.sql", p)
    ]
    unsupported = [
        p
        for p in ROOT.glob("*/src/main/resources/db/migration/*")
        if p.is_file() and not re.fullmatch(r"V[^/]+\.sql", p.name)
    ]
    if unsupported:
        raise ValueError(
            "Repeatable migrations and callbacks require a separate compatibility policy"
        )
    current = {
        p.relative_to(ROOT).as_posix(): sha(p)
        for p in ROOT.glob("*/src/main/resources/db/migration/V*.sql")
    }
    for name in historical:
        value = subprocess.check_output(
            [
                "git",
                "-c",
                "safe.directory=" + ROOT.as_posix(),
                "show",
                baseline + ":" + name,
            ],
            cwd=ROOT,
        )
        # Git normalizes tracked SQL to LF on every build host.
        expected = hashlib.sha256(value.replace(b"\r\n", b"\n")).hexdigest()
        if (
            name not in current
            or hashlib.sha256(
                (ROOT / name).read_bytes().replace(b"\r\n", b"\n")
            ).hexdigest()
            != expected
        ):
            raise ValueError("An accepted migration was removed or modified: " + name)
    additions = set(current) - set(historical)
    if additions != set(review.get("additions", {})):
        raise ValueError("New migrations require an explicit compatibility review")
    for name in additions:
        disposition = review["additions"][name]
        if (
            not disposition.get("rollbackCompatible")
            or disposition.get("sha256") != current[name]
        ):
            raise ValueError("Migration review does not match exact accepted SQL")
    return current, review


def assemble(output):
    tag = os.environ["GITHUB_REF_NAME"]
    if not TAG.fullmatch(tag):
        raise ValueError("Invalid tag")
    lock = json.loads(
        (ROOT / "infrastructure/release/runtime-images.lock.json").read_text()
    )
    infra = lock["services"]
    if not infra or not all(DIGEST.fullmatch(v) for v in infra.values()):
        raise ValueError("Complete infrastructure digest lock required")
    for service, reference in infra.items():
        scan = output / (service + "-infrastructure-scan.json")
        scan_image(reference, scan)
    migrations, review = migration_compatibility()
    write(output / "migration-review.json", review)
    bundle = {
        "schemaVersion": 1,
        "release": tag,
        "sourceRevision": os.environ["GITHUB_SHA"],
        "verifiedWorkflowRun": int(os.environ["VERIFIED_RUN"]),
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "images": [
            json.loads((output / (service + "-image.json")).read_text())
            for service in sorted(SERVICES)
        ],
        "infrastructure": infra,
        "infrastructureEvidence": {
            name: {
                "file": name + "-infrastructure-scan.json",
                "sha256": sha(output / (name + "-infrastructure-scan.json")),
            }
            for name in infra
        },
        "rollback": {
            "expandOnlyMigrations": True,
            "compatibleApplicationSources": [review["baselineRevision"]],
            "migrationReview": {
                "file": "migration-review.json",
                "sha256": sha(output / "migration-review.json"),
            },
            "policy": "Reuse an earlier verified application digest; never reverse a database migration or downgrade changed infrastructure storage.",
        },
        "migrationManifest": migrations,
    }
    validate_bundle(bundle, output)
    write(output / "release.json", bundle)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    one = commands.add_parser("image")
    one.add_argument("--service", required=True)
    one.add_argument("--image", required=True)
    one.add_argument("--output", type=Path, required=True)
    all_images = commands.add_parser("assemble")
    all_images.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    if args.command == "image":
        image(args.service, args.image, args.output)
    else:
        assemble(args.output)


if __name__ == "__main__":
    main()
