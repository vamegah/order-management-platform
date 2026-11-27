import copy
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import promote_release as promotion
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


class PromotionExecutionTest(unittest.TestCase):
    def setUp(self):
        import tempfile
        import json
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.state = self.root / "state"
        self.state.mkdir()
        sql = self.root / "infrastructure/postgres/monitoring.sql"
        sql.parent.mkdir(parents=True)
        sql.write_text("SELECT 1;", encoding="utf-8")
        self.previous = {"release": "v1.0.0", "sourceRevision": "a" * 40}
        self.reference = {"release": "v1.0.1", "sourceRevision": "b" * 40}
        self.current = self.state / "current.json"
        self.current.write_text(json.dumps(self.previous), encoding="utf-8")
        self.pending = self.state / "pending.json"
        self.target = {"images": [{"image": "app@sha256:" + "b" * 64}],
                       "infrastructure": {"redis": "redis@sha256:" + "c" * 64}}

    def execute(self, failed_stage=None):
        from types import SimpleNamespace
        from unittest.mock import patch
        import json
        stages = []

        def command(arguments, **kwargs):
            stage = arguments[2]
            stages.append(stage)
            # No runtime deployment pointer may change during scan, backup or pull.
            if stage in ("pull",) or stage == "exec" and "check" in arguments:
                self.assertEqual(json.loads(self.current.read_text()), self.previous)
                self.assertFalse(self.pending.exists())
            if stage in ("stop", "up"):
                self.assertEqual(json.loads(self.pending.read_text()), self.reference)
            backup = stage == "exec" and "check" in arguments
            failure = stage == failed_stage or failed_stage == "backup" and backup
            return SimpleNamespace(returncode=1 if failure else 0)

        with patch.object(promotion, "scan_image"), patch.object(
            promotion.subprocess, "run", side_effect=command
        ):
            promotion.apply_release(["docker", "compose"], self.root, self.target,
                                    self.previous, self.state, self.reference.copy())
        return stages

    def test_scan_rejection_preserves_accepted_and_preexisting_pending_state(self):
        import json
        from unittest.mock import patch
        for existing_pending in (False, True):
            with self.subTest(existing_pending=existing_pending):
                if existing_pending:
                    self.pending.write_text(json.dumps(self.previous), encoding="utf-8")
                before = self.pending.read_bytes() if existing_pending else None
                with patch.object(promotion, "scan_image", side_effect=PolicyError("blocked")), \
                     patch.object(promotion.subprocess, "run") as commands:
                    with self.assertRaises(PolicyError):
                        promotion.apply_release(["docker", "compose"], self.root, self.target,
                                                self.previous, self.state, self.reference.copy())
                    commands.assert_not_called()
                self.assertEqual(json.loads(self.current.read_text()), self.previous)
                self.assertEqual(self.pending.read_bytes() if self.pending.exists() else None, before)

    def test_backup_or_pull_rejection_never_marks_a_deployment_as_started(self):
        import json
        for stage in ("backup", "pull"):
            with self.subTest(stage=stage):
                with self.assertRaises(PolicyError):
                    self.execute(stage)
                self.assertFalse(self.pending.exists())
                self.assertEqual(json.loads(self.current.read_text()), self.previous)

    def test_runtime_failure_preserves_pending_and_previous_release_for_recovery(self):
        import json
        for stage in ("stop", "up"):
            with self.subTest(stage=stage):
                with self.assertRaises(PolicyError):
                    self.execute(stage)
                self.assertEqual(json.loads(self.pending.read_text()), self.reference)
                self.assertEqual(json.loads(self.current.read_text()), self.previous)
                self.pending.unlink()

    def test_success_accepts_the_release_and_removes_pending_state(self):
        import json
        self.assertIn("up", self.execute())
        self.assertFalse(self.pending.exists())
        accepted = json.loads(self.current.read_text())
        self.assertEqual(accepted["release"], self.reference["release"])
        self.assertIn("seconds", accepted)

    def test_interrupted_atomic_pointer_write_preserves_previous_complete_value(self):
        from unittest.mock import patch
        original = self.current.read_bytes()
        with patch.object(promotion.os, "replace", side_effect=OSError("controlled failure")):
            with self.assertRaises(OSError):
                promotion.write_state(self.current, self.reference)
        self.assertEqual(self.current.read_bytes(), original)
        self.assertEqual(list(self.state.glob(".current.json.*")), [])


if __name__ == "__main__":
    unittest.main()
