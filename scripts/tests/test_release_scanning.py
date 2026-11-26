import json
import os
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import release_scanning as scanning
from release_policy import PolicyError


class ReleaseScanTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.output = Path(self.temp.name) / "scan.json"
        self.image = "registry.example/app@sha256:" + "a" * 64
        self.report = {"SchemaVersion": 2, "ArtifactName": self.image,
                       "Metadata": {}, "Results": []}

    def write(self, report):
        self.output.write_text(json.dumps(report), encoding="utf-8")

    def test_scan_ignores_environment_and_configuration_suppression(self):
        self.write(self.report)
        environment = {"TRIVY_IGNORE_UNFIXED": "true", "TRIVY_SKIP_FILES": "**",
                       "TRIVY_VEX": "attacker.json", "TRIVY_CONFIG": "unsafe.yml",
                       "TRIVY_IGNORE_STATUS": "affected", "SSL_CERT_FILE": "/trust/ca.pem"}
        with patch.dict(os.environ, environment), patch.object(
            scanning.subprocess, "run", return_value=SimpleNamespace(returncode=0)
        ) as command:
            scanning.scan_image(self.image, self.output)
        args = command.call_args.args[0]
        for flag in ("--config", "--ignorefile", "--ignore-policy"):
            self.assertEqual(args[args.index(flag) + 1], "")
        self.assertIn("--ignore-unfixed=false", args)
        self.assertEqual(args[args.index("--exit-code") + 1], "1")
        env = command.call_args.kwargs["env"]
        self.assertFalse(any(k.startswith("TRIVY_") for k in env))
        self.assertEqual(env["SSL_CERT_FILE"], "/trust/ca.pem")

    def test_mutable_reference_is_rejected_before_execution(self):
        with patch.object(scanning.subprocess, "run") as command:
            for image in ("app:latest", "app:v1.2.3", "app", "sha256:invalid"):
                with self.subTest(image=image), self.assertRaises(PolicyError):
                    scanning.scan_image(image, self.output)
            command.assert_not_called()

    def test_complete_report_for_a_local_image_id_is_accepted(self):
        self.image = "sha256:" + "c" * 64
        self.report["ArtifactName"] = self.image
        self.write(self.report)
        with patch.object(scanning.subprocess, "run", return_value=SimpleNamespace(returncode=0)):
            self.assertEqual(scanning.scan_image(self.image, self.output), self.report)

    def test_nonzero_scanner_status_blocks_even_with_a_clean_or_stale_report(self):
        self.write(self.report)
        with patch.object(scanning.subprocess, "run", return_value=SimpleNamespace(returncode=1)):
            with self.assertRaises(PolicyError):
                scanning.scan_image(self.image, self.output)

    def test_missing_incomplete_foreign_and_vulnerable_reports_fail_closed(self):
        cases = [None, {}, {**self.report, "ArtifactName": "foreign"},
                 {**self.report, "Results": {}},
                 {**self.report, "Results": [{"Vulnerabilities": [{"Severity": "HIGH"}]}]},
                 {**self.report, "Results": [{"Vulnerabilities": [{"Severity": "CRITICAL"}]}]}]
        for report in cases:
            with self.subTest(report=report):
                self.output.unlink(missing_ok=True)
                if report is not None:
                    self.write(report)
                with patch.object(scanning.subprocess, "run", return_value=SimpleNamespace(returncode=0)):
                    with self.assertRaises(PolicyError):
                        scanning.scan_image(self.image, self.output)


if __name__ == "__main__":
    unittest.main()
