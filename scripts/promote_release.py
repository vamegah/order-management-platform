"""Promote verified immutable releases on a dedicated Linux Compose host.

The GitHub workflow enforces approval before invoking this command. No credentials are logged.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
from release_policy import PolicyError, SERVICES, verify_signatures, render
from check_production_configuration import check_model
from release_scanning import scan_image


def plan(current, target, action):
    if action not in ("promote", "rollback"):
        raise PolicyError("Unknown promotion action")
    if current:
        if current["infrastructure"] != target["infrastructure"]:
            raise PolicyError(
                "Stateful infrastructure changes require a separate reviewed upgrade; application rollback cannot downgrade storage"
            )
        old_pg = next(i for i in current["images"] if i["service"] == "postgres")
        new_pg = next(i for i in target["images"] if i["service"] == "postgres")
        if old_pg["image"] != new_pg["image"] and (
            not old_pg.get("runtimeSourceSha256")
            or old_pg.get("runtimeSourceSha256") != new_pg.get("runtimeSourceSha256")
        ):
            raise PolicyError(
                "PostgreSQL runtime changes require a separate reviewed upgrade"
            )
        before, after = current.get("migrationManifest", {}), target.get(
            "migrationManifest", {}
        )
        if action == "promote" and any(
            after.get(name) != digest for name, digest in before.items()
        ):
            raise PolicyError(
                "An accepted database migration cannot be changed or removed"
            )
        if action == "rollback":
            same_schema = before == after
            reviewed = target["sourceRevision"] in current.get("rollback", {}).get(
                "compatibleApplicationSources", []
            )
            if not same_schema and not reviewed:
                raise PolicyError(
                    "Rollback target has no signed database compatibility disposition"
                )
            if any(before.get(name) != digest for name, digest in after.items()):
                raise PolicyError(
                    "Rollback cannot reverse or replace database migrations"
                )
    elif action == "rollback":
        raise PolicyError("There is no accepted release to roll back")
    return {
        "release": target["release"],
        "sourceRevision": target["sourceRevision"],
        "action": action,
        "stopBeforeReplace": ["inventory-service", "payment-service"],
        "rebuild": False,
        "databaseDowngrade": False,
        "statefulImageDowngrade": False,
    }


def write_state(path, reference):
    """Publish a complete, private state pointer atomically, including after a crash."""
    descriptor, temporary = tempfile.mkstemp(prefix="." + path.name + ".", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as stream:
            json.dump(reference, stream)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        if os.name == "posix":
            directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
            try:
                os.fsync(directory)
            finally:
                os.close(directory)
    finally:
        Path(temporary).unlink(missing_ok=True)


def apply_release(base, root, target, current, state, reference):
    """Keep preflight rejection distinct from a deployment that might have changed data."""
    pending = state / "pending.json"
    started = time.monotonic()

    def apply(*arguments, input=None):
        result = subprocess.run(
            [*base, *arguments],
            cwd=root,
            input=input,
            capture_output=True,
            text=True,
            timeout=900,
        )
        if result.returncode:
            raise PolicyError(
                "Deployment stage failed: "
                + arguments[0]
                + "; signed pending and previous release evidence are preserved"
            )

    with tempfile.TemporaryDirectory(prefix="omp-release-scans-", dir=state) as scans:
        for index, image in enumerate(sorted(
            {item["image"] for item in target["images"]}
            | set(target["infrastructure"].values())
        )):
            scan_image(image, Path(scans) / (str(index) + ".json"))
    # Checking the archive is mandatory before modifying an existing deployment.
    if current:
        apply(
            "exec",
            "-T",
            "--user",
            "70:2000",
            "postgres",
            "/usr/local/bin/omp-pgbackrest",
            "check",
        )
    apply("pull", "--quiet")
    write_state(pending, reference)
    apply("stop", "--timeout", "30", "inventory-service", "payment-service")
    apply("up", "-d", "--no-build", "--wait", "--wait-timeout", "600")
    monitoring = (root / "infrastructure/postgres/monitoring.sql").read_text(
        encoding="utf-8"
    )
    apply(
        "exec",
        "-T",
        "postgres",
        "psql",
        "-X",
        "--set=ON_ERROR_STOP=1",
        "-U",
        "omp_admin",
        "-d",
        "omp",
        "-f",
        "-",
        input=monitoring,
    )
    reference["seconds"] = round(time.monotonic() - started, 2)
    write_state(state / "current.json", reference)
    pending.unlink()
    print(json.dumps({"accepted": True, **reference}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", type=Path, required=True)
    parser.add_argument("--signature", type=Path, required=True)
    parser.add_argument("--artifacts", type=Path, required=True)
    parser.add_argument("--source-directory", type=Path, required=True)
    parser.add_argument("--env-file", type=Path, required=True)
    parser.add_argument("--project", required=True)
    parser.add_argument("--state-directory", type=Path, required=True)
    parser.add_argument("--action", choices=("promote", "rollback"), default="promote")
    parser.add_argument(
        "--execute",
        action="store_true",
        help="Apply the reviewed plan; default only verifies and prints it",
    )
    args = parser.parse_args()
    if not re.fullmatch(r"omp(?:-[a-z0-9]{1,32})?", args.project):
        raise PolicyError("Invalid deployment project")
    if not args.env_file.is_absolute() or not args.env_file.is_file():
        raise PolicyError("A managed absolute non-secret environment file is required")
    root = args.source_directory.resolve()
    target = verify_signatures(args.bundle, args.signature, args.artifacts)
    head = subprocess.check_output(
        ["git", "-C", str(root), "rev-parse", "HEAD"], text=True
    ).strip()
    dirty = subprocess.check_output(
        ["git", "-C", str(root), "status", "--porcelain"], text=True
    ).strip()
    if head != target["sourceRevision"] or dirty:
        raise PolicyError(
            "Deployment source must be a clean checkout of the verified release revision"
        )
    state = args.state_directory
    if (
        not state.is_absolute()
        or state.is_symlink()
        or state.resolve() != state.absolute()
    ):
        raise PolicyError("A managed absolute non-symlink state directory is required")
    state.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(state, 0o700)
    if root.parent != state / "sources" or root.name != target["sourceRevision"]:
        raise PolicyError(
            "Deployment source must remain in the persistent managed source directory"
        )
    lock = (state / "deployment.lock").open("a+")
    if os.name != "posix":
        raise PolicyError(
            "Deployment verification and execution require the supported Linux host"
        )
    import fcntl

    fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    pointer = state / (
        "pending.json" if (state / "pending.json").exists() else "current.json"
    )
    current = None
    if pointer.exists():
        reference = json.loads(pointer.read_text())
        directory = (state / "releases" / reference["release"]).resolve()
        if (state / "releases").resolve() not in directory.parents:
            raise PolicyError("Stored release escapes managed state")
        current = verify_signatures(
            directory / "release.json", directory / "release.sigstore.json", directory
        )
    decision = plan(current, target, args.action)
    with tempfile.TemporaryDirectory(prefix="omp-promotion-", dir=state) as temporary:
        overlay = Path(temporary) / "compose.release.yml"
        overlay.write_text(render(target), encoding="utf-8")
        base = [
            "docker",
            "compose",
            "-p",
            args.project,
            "--env-file",
            str(args.env_file),
            "-f",
            str(root / "docker-compose.prod.yml"),
            "-f",
            str(overlay),
        ]
        model = json.loads(
            subprocess.check_output(
                [*base, "config", "--format", "json"], cwd=root, text=True
            )
        )
        check_model(model, target["infrastructure"])
        print(json.dumps(decision), flush=True)
        if not args.execute:
            return
        # Keep only cryptographically bound evidence; never copy arbitrary auxiliary artifacts.
        destination = state / "releases" / target["release"]
        destination.mkdir(parents=True, exist_ok=True, mode=0o700)
        proofs = {
            item[kind]["file"]
            for item in target["images"]
            for kind in ("sbom", "scan", "provenance")
        }
        proofs.update(
            item["file"] for item in target["infrastructureEvidence"].values()
        )
        if target.get("rollback", {}).get("migrationReview"):
            proofs.add(target["rollback"]["migrationReview"]["file"])
        pairs = [
            (args.bundle, "release.json"),
            (args.signature, "release.sigstore.json"),
        ]
        pairs += [(args.artifacts / name, name) for name in proofs]
        for source, name in pairs:
            source = source.resolve()
            output = destination / name
            output.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
            value = source.read_bytes()
            if output.exists():
                if output.read_bytes() != value:
                    raise PolicyError(
                        "Refusing to replace an existing immutable release"
                    )
            else:
                with output.open("xb") as stream:
                    os.fchmod(stream.fileno(), 0o600)
                    stream.write(value)
        retained_overlay = destination / "compose.release.yml"
        rendered = render(target)
        if retained_overlay.exists():
            if retained_overlay.read_text(encoding="utf-8") != rendered:
                raise PolicyError("Refusing to replace an accepted release overlay")
        else:
            with retained_overlay.open("x", encoding="utf-8") as stream:
                os.fchmod(stream.fileno(), 0o600)
                stream.write(rendered)
        reference = {
            "schemaVersion": 1,
            "sourceDirectory": str(root),
            "overlay": str(retained_overlay),
            "envFile": str(args.env_file),
            "release": target["release"],
            "sourceRevision": target["sourceRevision"],
            "project": args.project,
        }
        apply_release(base, root, target, current, state, reference)


if __name__ == "__main__":
    main()
