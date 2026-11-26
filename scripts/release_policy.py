"""Validate immutable release inputs and cryptographic evidence before rendering deployment."""
import argparse, base64, hashlib, json, re, subprocess, sys
from datetime import datetime, timezone
from pathlib import Path

SERVICES = {
    "product-service",
    "customer-service",
    "order-service",
    "graphql-gateway",
    "inventory-service",
    "payment-service",
    "notification-service",
    "legacy-banking-adapter",
    "legacy-bank-service",
    "postgres",
}
REPOSITORY = "vamegah/order-management-platform"
OIDC_ISSUER = "https://token.actions.githubusercontent.com"
DIGEST = re.compile(r"[a-z0-9][a-z0-9./:_-]*@sha256:[0-9a-f]{64}")
RUNTIME_SERVICES = frozenset(
    json.loads(
        (
            Path(__file__).resolve().parents[1]
            / "infrastructure/release/runtime-images.lock.json"
        ).read_text(encoding="utf-8")
    )["services"]
)
MIGRATION = re.compile(r"[a-z-]+/src/main/resources/db/migration/V[^/]+\.sql")
SHA256 = re.compile(r"[0-9a-f]{64}")
TAG = re.compile(r"v[0-9]+\.[0-9]+\.[0-9]+(?:-[a-z0-9.]+)?")


class PolicyError(ValueError):
    pass


def digest_file(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def validate_bundle(bundle, artifacts):
    if bundle.get("schemaVersion") != 1 or not TAG.fullmatch(bundle.get("release", "")):
        raise PolicyError("Invalid release identity")
    revision = bundle.get("sourceRevision", "")
    if not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise PolicyError("Invalid source revision")
    images = bundle.get("images", [])
    if len(images) != len(SERVICES) or {i.get("service") for i in images} != SERVICES:
        raise PolicyError(
            "Release image catalogue is incomplete or contains debug/test services"
        )
    for item in images:
        image = item.get("image", "")
        if not DIGEST.fullmatch(image) or not image.startswith(
            "ghcr.io/" + REPOSITORY + "/" + item["service"] + "@"
        ):
            raise PolicyError(
                "Image must use the expected repository and immutable digest"
            )
        if item.get("sourceRevision") != revision:
            raise PolicyError("Image source revision does not match release")
        for field in ("sbom", "scan", "provenance"):
            proof = item.get(field, {})
            relative = Path(proof.get("file", ""))
            target = (artifacts / relative).resolve()
            if (
                relative.is_absolute()
                or artifacts.resolve() not in target.parents
                or not target.is_file()
            ):
                raise PolicyError(
                    "Evidence path is missing or escapes the release bundle"
                )
            if digest_file(target) != proof.get("sha256"):
                raise PolicyError("Release evidence hash mismatch")
        scan = json.loads(
            (artifacts / item["scan"]["file"]).read_text(encoding="utf-8")
        )
        if (
            scan.get("SchemaVersion") != 2
            or not isinstance(scan.get("Metadata"), dict)
            or "Results" not in scan
        ):
            raise PolicyError("Missing or incomplete image scan")
        if scan.get("ArtifactName") != image:
            raise PolicyError("Scan is bound to a different image")
        for result in scan["Results"] or []:
            for finding in result.get("Vulnerabilities") or []:
                if finding.get("Severity") in ("HIGH", "CRITICAL"):
                    raise PolicyError("Release image violates vulnerability policy")
        sbom = json.loads(
            (artifacts / item["sbom"]["file"]).read_text(encoding="utf-8")
        )
        if sbom.get("bomFormat") != "CycloneDX" or not sbom.get("components"):
            raise PolicyError("Missing image SBOM components")
        provenance = json.loads(
            (artifacts / item["provenance"]["file"]).read_text(encoding="utf-8")
        )
        if (
            provenance.get("sourceRevision") != revision
            or provenance.get("image") != image
        ):
            raise PolicyError("Provenance subject mismatch")
    upstream = bundle.get("infrastructure", {})
    if set(upstream) != RUNTIME_SERVICES or not all(
        DIGEST.fullmatch(image) for image in upstream.values()
    ):
        raise PolicyError("Infrastructure must also be digest pinned")
    evidence = bundle.get("infrastructureEvidence", {})
    if set(evidence) != set(upstream):
        raise PolicyError("Infrastructure scan catalogue is incomplete")
    for name, reference in upstream.items():
        report = evidence[name]
        relative = Path(report.get("file", ""))
        target = (artifacts / relative).resolve()
        if (
            relative.is_absolute()
            or artifacts.resolve() not in target.parents
            or not target.is_file()
        ):
            raise PolicyError(
                "Infrastructure evidence path is missing or escapes bundle"
            )
        if digest_file(target) != report.get("sha256"):
            raise PolicyError("Infrastructure scan hash mismatch")
        scan = json.loads(target.read_text(encoding="utf-8"))
        if (
            scan.get("SchemaVersion") != 2
            or scan.get("ArtifactName") != reference
            or not isinstance(scan.get("Metadata"), dict)
            or "Results" not in scan
        ):
            raise PolicyError("Infrastructure scan subject mismatch or incomplete scan")
        if any(
            v.get("Severity") in ("HIGH", "CRITICAL")
            for r in scan["Results"] or []
            for v in r.get("Vulnerabilities") or []
        ):
            raise PolicyError("Infrastructure violates vulnerability policy")
    if not bundle.get("verifiedWorkflowRun") or not bundle.get("rollback", {}).get(
        "expandOnlyMigrations"
    ):
        raise PolicyError(
            "Release lacks verification or migration compatibility evidence"
        )
    postgres = next(item for item in images if item["service"] == "postgres")
    if not SHA256.fullmatch(postgres.get("runtimeSourceSha256", "")):
        raise PolicyError("Missing PostgreSQL runtime source binding")
    manifest = bundle.get("migrationManifest", {})
    if not manifest or not all(
        MIGRATION.fullmatch(name) and SHA256.fullmatch(value)
        for name, value in manifest.items()
    ):
        raise PolicyError("Invalid versioned migration manifest")
    rollback = bundle["rollback"]
    proof = rollback.get("migrationReview", {})
    relative = Path(proof.get("file", ""))
    target = (artifacts / relative).resolve()
    if (
        relative.is_absolute()
        or artifacts.resolve() not in target.parents
        or not target.is_file()
    ):
        raise PolicyError("Migration review path is missing or escapes bundle")
    if digest_file(target) != proof.get("sha256"):
        raise PolicyError("Migration review hash mismatch")
    review = json.loads(target.read_text(encoding="utf-8"))
    baseline = review.get("baselineRevision", "")
    if review.get("schemaVersion") != 1 or not re.fullmatch(r"[0-9a-f]{40}", baseline):
        raise PolicyError("Invalid migration compatibility review")
    if rollback.get("compatibleApplicationSources") != [baseline]:
        raise PolicyError("Rollback source lacks the exact compatibility review")
    for name, disposition in review.get("additions", {}).items():
        if (
            not MIGRATION.fullmatch(name)
            or disposition.get("rollbackCompatible") is not True
            or disposition.get("sha256") != manifest.get(name)
        ):
            raise PolicyError(
                "Migration compatibility review does not match SQL manifest"
            )
    return bundle


def verify_signatures(bundle_path, signature, artifacts, cosign="cosign"):
    # Verify before interpreting any deployable configuration from the file.
    data = json.loads(bundle_path.read_text(encoding="utf-8"))
    release = data.get("release", "")
    if not TAG.fullmatch(release):
        raise PolicyError("Invalid release tag")
    identity = (
        "https://github.com/"
        + REPOSITORY
        + "/.github/workflows/release.yml@refs/tags/"
        + release
    )
    args = [
        "--certificate-identity",
        identity,
        "--certificate-oidc-issuer",
        OIDC_ISSUER,
        "--certificate-github-workflow-sha",
        data.get("sourceRevision", ""),
        "--certificate-github-workflow-repository",
        REPOSITORY,
    ]
    subprocess.run(
        [cosign, "verify-blob", *args, "--bundle", str(signature), str(bundle_path)],
        check=True,
        capture_output=True,
        timeout=60,
    )
    validate_bundle(data, artifacts)
    for item in data["images"]:
        subprocess.run(
            [cosign, "verify", *args, item["image"]],
            check=True,
            capture_output=True,
            timeout=60,
        )
        for field, kind in (
            ("sbom", "https://cyclonedx.org/bom"),
            ("provenance", "https://slsa.dev/provenance/v1"),
        ):
            result = subprocess.run(
                [
                    cosign,
                    "verify-attestation",
                    *args,
                    "--type",
                    kind,
                    "--output",
                    "json",
                    item["image"],
                ],
                check=True,
                capture_output=True,
                timeout=60,
            )
            expected = json.loads(
                (artifacts / item[field]["file"]).read_text(encoding="utf-8")
            )
            validate_attestations(result.stdout, kind, item["image"], expected)
    return data


def validate_attestations(raw, kind, image, expected):
    try:
        text = raw.decode("utf-8") if isinstance(raw, bytes) else raw
        try:
            envelopes = json.loads(text)
            if isinstance(envelopes, dict):
                envelopes = [envelopes]
        except json.JSONDecodeError:
            envelopes = [json.loads(line) for line in text.splitlines() if line.strip()]
        for envelope in envelopes:
            statement = json.loads(base64.b64decode(envelope["payload"], validate=True))
            subject = any(
                s.get("digest", {}).get("sha256") == image.split("@sha256:")[1]
                for s in statement.get("subject", [])
            )
            if (
                subject
                and statement.get("predicateType") == kind
                and statement.get("predicate") == expected
            ):
                return
    except (ValueError, TypeError, KeyError):
        raise PolicyError("Malformed verified attestation") from None
    raise PolicyError("Verified attestation does not match exact release evidence")


def validate_environment(environment):
    rules = environment.get("protection_rules", [])
    approval = next((r for r in rules if r.get("type") == "required_reviewers"), None)
    if (
        not approval
        or not approval.get("reviewers")
        or approval.get("prevent_self_review") is not True
    ):
        raise PolicyError(
            "Promotion requires named reviewers and prevention of self approval"
        )
    if environment.get("can_admins_bypass") is not False:
        raise PolicyError("Promotion cannot allow administrator bypass")
    deployment = environment.get("deployment_branch_policy") or {}
    if not deployment.get("protected_branches") and not deployment.get(
        "custom_branch_policies"
    ):
        raise PolicyError("Promotion branch policy is not restricted")
    return True


def validate_compose(model):
    for name, service in model.get("services", {}).items():
        if service.get("profiles"):
            continue
        if name in (
            "payment-provider-simulator",
            "pgadmin",
            "kafka-ui",
            "alert-receiver",
        ):
            raise PolicyError("Test/debug service is active in a production release")
        if not DIGEST.fullmatch(service.get("image", "")):
            raise PolicyError("Unpinned deployment image: " + name)
        if service.get("build"):
            raise PolicyError("Promotion must not rebuild images")
        if service.get("privileged") or service.get("network_mode") == "host":
            raise PolicyError("Privileged or host network deployment")
        for port in service.get("ports", []):
            if name != "traefik":
                raise PolicyError("Private service publishes a host port: " + name)
            if str(port.get("published")) not in ("80", "443"):
                raise PolicyError("Unexpected public ingress port")
        for key, value in service.get("environment", {}).items():
            if value is None or str(value) == "":
                continue
            if re.search(
                r"PASSWORD$|SECRET$|API_KEY$|(^|_)TOKEN$", key, re.I
            ) and not key.endswith("_FILE"):
                raise PolicyError(
                    "Secret value supplied through deployment environment: "
                    + name
                    + "/"
                    + key
                )
        if name in SERVICES - {"postgres"}:
            env = service.get("environment", {})
            if (
                name != "legacy-bank-service"
                and str(env.get("API_QUOTAS_ENABLED", "")).lower() != "true"
            ):
                raise PolicyError("Public service shared quotas are disabled: " + name)
            if name != "legacy-bank-service" and not service.get("secrets"):
                raise PolicyError("Service lacks mounted secrets")
    return {"services": len(model.get("services", {})), "immutable": True}


def render(bundle):
    images = {item["service"]: item["image"] for item in bundle["images"]}
    images.update(bundle["infrastructure"])
    # Compose override tags remove inherited build declarations explicitly.
    return (
        "# Verified release "
        + bundle["release"]
        + " from "
        + bundle["sourceRevision"]
        + "\nservices:\n"
        + "".join(
            "  " + name + ":\n    image: " + image + "\n    build: !reset null\n"
            for name, image in sorted(images.items())
        )
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    compose = sub.add_parser("compose")
    compose.add_argument("input", type=Path)
    environment = sub.add_parser("environment")
    environment.add_argument("input", type=Path)
    verify = sub.add_parser("verify")
    verify.add_argument("--bundle", type=Path, required=True)
    verify.add_argument("--signature", type=Path, required=True)
    verify.add_argument("--artifacts", type=Path, required=True)
    verify.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "compose":
        print(
            json.dumps(
                validate_compose(json.loads(args.input.read_text(encoding="utf-8-sig")))
            )
        )
    elif args.command == "environment":
        validate_environment(json.loads(args.input.read_text(encoding="utf-8-sig")))
        print('{"protectedEnvironment":true}')
    else:
        bundle = verify_signatures(args.bundle, args.signature, args.artifacts)
        with args.output.open("x", encoding="utf-8", newline="\n") as target:
            target.write(render(bundle))
        print('{"verified":true,"promotedByDigest":true}')


if __name__ == "__main__":
    main()
