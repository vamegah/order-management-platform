import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


renderer = load("render_alertmanager", Path("render_alertmanager.py"))
faults = load("faults", Path("operations/faults.py"))
secrets_tool = load("prepare_secrets", Path("prepare_production_secrets.py"))


class OperationsControlTest(unittest.TestCase):
    def test_fresh_secrets_are_file_only_and_bank_credentials_match_bootstrap(self):
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            users = parent / "users"
            users.write_text("operator:$2b$12$" + "a" * 53)
            directory = parent / "secrets"
            result = secrets_tool.prepare(directory, users)
            ledger = (directory / "bank-ledger/spring.datasource.password").read_text()
            self.assertEqual(
                ledger, (directory / "bank_ledger_svc_password").read_text()
            )
            self.assertEqual(
                (directory / "bank-ledger/bank.security.password").read_text(),
                (directory / "bank-adapter/bank.ws.password").read_text(),
            )
            self.assertNotIn(ledger, str(result))
            self.assertFalse(result["credentialValuesPrinted"])
            self.assertFalse((directory / "stripe_api_key").exists())
            self.assertTrue((directory / "redis_exporter_passwords").is_file())
            with self.assertRaisesRegex(ValueError, "overwrite"):
                secrets_tool.prepare(directory, users)
            # chmod allows Windows TemporaryDirectory to remove its read-only test leaves.
            for leaf in directory.rglob("*"):
                if leaf.is_file():
                    leaf.chmod(0o600)

    def test_secret_setup_rejects_weak_hash_and_relative_or_occupied_directory(self):
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            users = parent / "users"
            users.write_text("operator:$2b$04$" + "a" * 53)
            with self.assertRaises(ValueError):
                secrets_tool.prepare(parent / "secrets", users)
            with self.assertRaises(ValueError):
                secrets_tool.prepare(Path("relative"), users)
            self.assertFalse((parent / "secrets").exists())

    def test_renderer_uses_mounted_token_without_redirects_or_dotenv_expansion(self):
        config = renderer.render("https://alerts.example.invalid/receiver")
        for receiver in config["receivers"]:
            http = receiver["webhook_configs"][0]["http_config"]
            self.assertFalse(http["follow_redirects"])
            self.assertIn("credentials_file", http["authorization"])

    def test_receiver_rejects_plain_http_credentials_query_fragment_and_invalid_input(
        self,
    ):
        for url in (
            "http://alerts.example.invalid",
            "https://user:pass@alerts.example.invalid",
            "https://alerts.example.invalid?token=secret",
            "https://alerts.example.invalid#fragment",
            "https://",
            "https://alerts.example.invalid/has space",
        ):
            with self.assertRaises(ValueError):
                renderer.render(url)

    def test_faults_cannot_target_other_projects_or_unbounded_durations(self):
        with patch.object(faults.subprocess, "run") as execute:
            for project in (
                "omp",
                "production",
                "omp-operations-",
                "../omp-operations-123456",
            ):
                with self.assertRaises(ValueError):
                    faults.inspect_container("docker", project, "postgres")
            for seconds in (0, 31, 999):
                with self.assertRaises(ValueError):
                    faults.inject(
                        "docker", "omp-operations-123456", "postgres", seconds
                    )
            with self.assertRaises(ValueError):
                faults.inject("docker", "omp-operations-123456", "unknown", 1)
            execute.assert_not_called()

    def test_resume_is_guaranteed_if_fault_wait_is_interrupted(self):
        with patch.object(
            faults, "inspect_container", return_value="owned-id"
        ), patch.object(faults.subprocess, "run") as execute, patch.object(
            faults.time, "sleep", side_effect=KeyboardInterrupt
        ):
            with self.assertRaises(KeyboardInterrupt):
                faults.inject("docker", "omp-operations-123456", "postgres", 1)
            self.assertEqual(
                execute.call_args_list[-1].args[0], ["docker", "unpause", "owned-id"]
            )


if __name__ == "__main__":
    unittest.main()
