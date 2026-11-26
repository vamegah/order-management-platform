import copy
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from promote_release import plan
from release_policy import PolicyError


class PromotionTest(unittest.TestCase):
    def setUp(self):
        self.bundle = {
            "release": "v1.0.0",
            "sourceRevision": "a" * 40,
            "images": [
                {
                    "service": "postgres",
                    "image": "postgres@sha256:" + "b" * 64,
                    "runtimeSourceSha256": "c" * 64,
                }
            ],
            "infrastructure": {"redis": "redis@sha256:" + "d" * 64},
            "migrationManifest": {"old.sql": "e" * 64},
            "rollback": {"compatibleApplicationSources": ["a" * 40]},
        }

    def test_same_schema_application_promotion_and_rollback_are_digest_only(self):
        for action in ("promote", "rollback"):
            result = plan(self.bundle, self.bundle, action)
            self.assertFalse(result["rebuild"])
            self.assertFalse(result["databaseDowngrade"])
            self.assertEqual(
                result["stopBeforeReplace"], ["inventory-service", "payment-service"]
            )

    def test_postgres_rebuild_is_allowed_only_with_identical_runtime_inputs(self):
        target = copy.deepcopy(self.bundle)
        target["images"][0]["image"] = "postgres@sha256:" + "f" * 64
        self.assertTrue(plan(self.bundle, target, "promote"))
        target["images"][0]["runtimeSourceSha256"] = "0" * 64
        with self.assertRaises(PolicyError):
            plan(self.bundle, target, "promote")

    def test_unreviewed_schema_and_storage_downgrades_are_rejected(self):
        for mutation in (
            lambda b: b["infrastructure"].update(redis="redis@sha256:" + "f" * 64),
            lambda b: b["migrationManifest"].update({"old.sql": "0" * 64}),
            lambda b: b["migrationManifest"].clear(),
        ):
            target = copy.deepcopy(self.bundle)
            mutation(target)
            with self.assertRaises(PolicyError):
                plan(self.bundle, target, "promote")
        current = copy.deepcopy(self.bundle)
        current["sourceRevision"] = "b" * 40
        current["migrationManifest"]["new.sql"] = "f" * 64
        current["rollback"]["compatibleApplicationSources"] = []
        with self.assertRaises(PolicyError):
            plan(current, self.bundle, "rollback")
        current["rollback"]["compatibleApplicationSources"] = [
            self.bundle["sourceRevision"]
        ]
        self.assertTrue(plan(current, self.bundle, "rollback"))

    def test_no_rollback_without_an_accepted_release(self):
        with self.assertRaises(PolicyError):
            plan(None, self.bundle, "rollback")


if __name__ == "__main__":
    unittest.main()
