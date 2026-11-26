import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location(
    "release_policy", Path(__file__).resolve().parents[1] / "release_policy.py"
)
policy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(policy)


class ReleasePolicyTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.bundle = {
            "schemaVersion": 1,
            "release": "v1.0.0",
            "sourceRevision": "a" * 40,
            "verifiedWorkflowRun": 123,
            "rollback": {"expandOnlyMigrations": True},
            "infrastructure": {"redis": "redis@sha256:" + "c" * 64},
            "images": [],
        }
        for service in sorted(policy.SERVICES):
            image = (
                "ghcr.io/" + policy.REPOSITORY + "/" + service + "@sha256:" + "b" * 64
            )
            item = {"service": service, "image": image, "sourceRevision": "a" * 40}
            for kind, value in {
                "sbom": {"bomFormat": "CycloneDX", "components": [{"name": "runtime"}]},
                "scan": {
                    "SchemaVersion": 2,
                    "Metadata": {},
                    "ArtifactName": image,
                    "Results": [],
                },
                "provenance": {"sourceRevision": "a" * 40, "image": image},
            }.items():
                source = self.directory / (service + "-" + kind + ".json")
                source.write_text(json.dumps(value), encoding="utf-8")
                item[kind] = {"file": source.name, "sha256": policy.digest_file(source)}
            if service == "postgres":
                item["runtimeSourceSha256"] = "d" * 64
            self.bundle["images"].append(item)
        lock = json.loads(
            (
                Path(__file__).resolve().parents[2]
                / "infrastructure/release/runtime-images.lock.json"
            ).read_text()
        )["services"]
        self.bundle["infrastructure"] = lock
        self.bundle["infrastructureEvidence"] = {}
        for name, image in lock.items():
            scan = self.directory / (name + "-scan.json")
            scan.write_text(
                json.dumps(
                    {
                        "SchemaVersion": 2,
                        "Metadata": {},
                        "ArtifactName": image,
                        "Results": [],
                    }
                )
            )
            self.bundle["infrastructureEvidence"][name] = {
                "file": scan.name,
                "sha256": policy.digest_file(scan),
            }
        migration = "notification-service/src/main/resources/db/migration/V6__trace.sql"
        self.bundle["migrationManifest"] = {migration: "e" * 64}
        review = self.directory / "migration-review.json"
        review.write_text(
            json.dumps(
                {
                    "schemaVersion": 1,
                    "baselineRevision": "f" * 40,
                    "additions": {
                        migration: {"sha256": "e" * 64, "rollbackCompatible": True}
                    },
                }
            )
        )
        self.bundle["rollback"].update(
            compatibleApplicationSources=["f" * 40],
            migrationReview={"file": review.name, "sha256": policy.digest_file(review)},
        )

    def test_attestation_must_match_subject_type_and_exact_predicate(self):
        import base64

        image = self.bundle["images"][0]["image"]
        expected = {"components": [{"name": "runtime"}]}
        statement = {
            "subject": [{"digest": {"sha256": "b" * 64}}],
            "predicateType": "https://cyclonedx.org/bom",
            "predicate": expected,
        }

        def envelope(value):
            return json.dumps(
                {"payload": base64.b64encode(json.dumps(value).encode()).decode()}
            ).encode()

        policy.validate_attestations(
            envelope(statement), statement["predicateType"], image, expected
        )
        for mutation in (
            lambda s: s.update(predicate={}),
            lambda s: s.update(predicateType="other"),
            lambda s: s.update(subject=[]),
        ):
            candidate = copy.deepcopy(statement)
            mutation(candidate)
            with self.assertRaises(policy.PolicyError):
                policy.validate_attestations(
                    envelope(candidate), statement["predicateType"], image, expected
                )

    def test_infrastructure_scan_must_be_complete_and_bound(self):
        for mutation in (
            lambda b: b.update(infrastructureEvidence={}),
            lambda b: b["infrastructureEvidence"]["redis"].update(sha256="a" * 64),
        ):
            candidate = copy.deepcopy(self.bundle)
            mutation(candidate)
            with self.assertRaises(policy.PolicyError):
                policy.validate_bundle(candidate, self.directory)

    def test_unreviewed_migrations_runtime_sources_and_extra_services_are_rejected(
        self,
    ):
        for mutation in (
            lambda b: b.update(migrationManifest={}),
            lambda b: b["migrationManifest"].update({"outside.sql": "e" * 64}),
            lambda b: b["rollback"].update(compatibleApplicationSources=["0" * 40]),
            lambda b: b["rollback"]["migrationReview"].update(sha256="0" * 64),
            lambda b: next(i for i in b["images"] if i["service"] == "postgres").pop(
                "runtimeSourceSha256"
            ),
            lambda b: b["infrastructure"].update(
                unreviewed="alpine@sha256:" + "0" * 64
            ),
        ):
            candidate = copy.deepcopy(self.bundle)
            mutation(candidate)
            with self.assertRaises(policy.PolicyError):
                policy.validate_bundle(candidate, self.directory)

    def test_complete_immutable_release_renders_without_building(self):
        policy.validate_bundle(self.bundle, self.directory)
        rendered = policy.render(self.bundle)
        self.assertIn("build: !reset null", rendered)
        self.assertNotIn("SNAPSHOT", rendered)
        self.assertNotIn("simulator", rendered)

    def test_modified_evidence_is_rejected(self):
        source = self.directory / self.bundle["images"][0]["sbom"]["file"]
        source.write_text("tampered", encoding="utf-8")
        with self.assertRaisesRegex(policy.PolicyError, "hash mismatch"):
            policy.validate_bundle(self.bundle, self.directory)

    def test_missing_test_extra_or_foreign_revision_image_is_rejected(self):
        for mutation in (
            lambda b: b["images"].pop(),
            lambda b: b["images"].append(copy.deepcopy(b["images"][0])),
            lambda b: b["images"][0].update(sourceRevision="d" * 40),
            lambda b: b["images"][0].update(
                image="ghcr.io/foreign/image@sha256:" + "b" * 64
            ),
        ):
            bundle = copy.deepcopy(self.bundle)
            mutation(bundle)
            with self.assertRaises(policy.PolicyError):
                policy.validate_bundle(bundle, self.directory)

    def test_high_and_critical_images_fail_even_if_report_hash_matches(self):
        for severity in ("HIGH", "CRITICAL"):
            bundle = copy.deepcopy(self.bundle)
            item = bundle["images"][0]
            source = self.directory / item["scan"]["file"]
            report = json.loads(source.read_text())
            report["Results"] = [
                {
                    "Vulnerabilities": [
                        {"Severity": severity, "VulnerabilityID": "CONTROLLED"}
                    ]
                }
            ]
            source.write_text(json.dumps(report))
            item["scan"]["sha256"] = policy.digest_file(source)
            with self.assertRaisesRegex(policy.PolicyError, "vulnerability"):
                policy.validate_bundle(bundle, self.directory)

    def test_evidence_path_traversal_and_unsigned_bundle_fail(self):
        bundle = copy.deepcopy(self.bundle)
        bundle["images"][0]["scan"]["file"] = "../scan.json"
        with self.assertRaisesRegex(policy.PolicyError, "escapes"):
            policy.validate_bundle(bundle, self.directory)
        source = self.directory / "release.json"
        source.write_text(json.dumps(self.bundle))
        import subprocess

        with patch.object(
            policy.subprocess,
            "run",
            side_effect=subprocess.CalledProcessError(1, "cosign"),
        ) as execute:
            with self.assertRaises(subprocess.CalledProcessError):
                policy.verify_signatures(
                    source, self.directory / "unsigned.sigstore.json", self.directory
                )
            self.assertEqual(execute.call_count, 1)

    def test_environment_requires_distinct_approvers_no_admin_bypass_and_branch_policy(
        self,
    ):
        environment = {
            "protection_rules": [
                {
                    "type": "required_reviewers",
                    "prevent_self_review": True,
                    "reviewers": [
                        {"type": "User", "reviewer": {"login": "release-approver"}}
                    ],
                }
            ],
            "can_admins_bypass": False,
            "deployment_branch_policy": {"protected_branches": True},
        }
        self.assertTrue(policy.validate_environment(environment))
        for mutation in (
            lambda e: e.update(protection_rules=[]),
            lambda e: e.update(can_admins_bypass=True),
            lambda e: e.update(deployment_branch_policy=None),
            lambda e: e["protection_rules"][0].update(prevent_self_review=False),
            lambda e: e["protection_rules"][0].update(reviewers=[]),
        ):
            candidate = copy.deepcopy(environment)
            mutation(candidate)
            with self.assertRaises(policy.PolicyError):
                policy.validate_environment(candidate)

    def test_production_manifest_rejects_unpinned_debug_ports_and_raw_secrets(self):
        clean = {
            "services": {
                "redis": {
                    "image": "redis@sha256:" + "f" * 64,
                    "environment": {},
                    "ports": [],
                }
            }
        }
        self.assertTrue(policy.validate_compose(clean)["immutable"])
        for mutation in (
            lambda x: x["services"]["redis"].update(image="redis:latest"),
            lambda x: x["services"]["redis"].update(build={"context": "."}),
            lambda x: x["services"]["redis"].update(ports=[{"published": "6379"}]),
            lambda x: x["services"]["redis"].update(
                environment={"REDIS_PASSWORD": "sensitive"}
            ),
            lambda x: x["services"].update(
                {"payment-provider-simulator": {"image": "fixture@sha256:" + "f" * 64}}
            ),
        ):
            candidate = copy.deepcopy(clean)
            mutation(candidate)
            with self.assertRaises(policy.PolicyError):
                policy.validate_compose(candidate)


if __name__ == "__main__":
    unittest.main()
