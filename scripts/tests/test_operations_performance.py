import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from verify_operations_performance import fixture


class PerformanceFixtureTest(unittest.TestCase):
    def test_refund_fixtures_reject_repeated_payments_invalid_money_and_missing_samples(
        self,
    ):
        valid = {
            "customerId": str(uuid.uuid4()),
            "productId": str(uuid.uuid4()),
            "refundPayments": [
                {"id": str(uuid.uuid4()), "amountMinor": 100} for _ in range(10)
            ],
        }
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "fixture.json"
            path.write_text(json.dumps(valid))
            self.assertEqual(fixture(path), valid)
            for mutate in (
                lambda d: d["refundPayments"].append(d["refundPayments"][0]),
                lambda d: d["refundPayments"].pop(),
                lambda d: d["refundPayments"][0].update(amountMinor=True),
                lambda d: d["refundPayments"][0].update(amountMinor=-100),
                lambda d: d.update(customerId="foreign"),
            ):
                data = copy.deepcopy(valid)
                mutate(data)
                path.write_text(json.dumps(data))
                with self.assertRaises(ValueError):
                    fixture(path)

    def test_load_cannot_share_identities_or_send_credentials_to_an_external_issuer(
        self,
    ):
        valid = {
            "tokenUrl": "http://keycloak:8080/realms/omp/protocol/openid-connect/token",
            "clients": [
                {"clientId": "isolated-" + str(i), "clientSecret": "disposable-fixture"}
                for i in range(32)
            ],
        }
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "identities.json"
            path.write_text(json.dumps(valid))
            path.chmod(0o600)
            self.assertEqual(fixture(path, identities=True), valid)
            for mutate in (
                lambda d: d.update(tokenUrl="https://external.invalid/token"),
                lambda d: d["clients"].append(d["clients"][0]),
                lambda d: d["clients"].pop(),
                lambda d: d["clients"][0].update(clientSecret=""),
            ):
                data = copy.deepcopy(valid)
                mutate(data)
                path.write_text(json.dumps(data))
                with self.assertRaises(ValueError):
                    fixture(path, identities=True)


class ProvisioningGuardTest(unittest.TestCase):
    def test_cannot_provision_external_provider_or_unowned_stack(self):
        import argparse
        from unittest.mock import patch
        import prepare_performance_fixtures as provisioning

        with tempfile.TemporaryDirectory() as temporary:
            arguments = argparse.Namespace(
                project="production",
                docker="docker",
                directory=Path(temporary) / "private",
            )
            with patch.dict(
                provisioning.os.environ, {"OMP_ACCEPTANCE_FIXTURES": "true"}
            ), patch.object(
                provisioning.subprocess, "check_output"
            ) as execute, patch.object(
                provisioning, "build_opener"
            ) as opener:
                with self.assertRaises(ValueError):
                    provisioning.prepare(arguments)
                execute.assert_not_called()
                opener.assert_not_called()
                arguments.project = "omp-operations-123456"
                items = [
                    {
                        "Config": {
                            "Labels": {
                                "com.omp.acceptance": "operations",
                                "com.docker.compose.service": name,
                            },
                            "Env": ["PAYMENT_PROVIDER=stripe"],
                        }
                    }
                    for name in ("keycloak", "payment-service", "traefik")
                ]
                execute.side_effect = ["owned\n", json.dumps(items)]
                with self.assertRaisesRegex(ValueError, "external charges"):
                    provisioning.prepare(arguments)
                opener.assert_not_called()
                items[0]["Config"]["Labels"]["com.omp.acceptance"] = "foreign"
                execute.side_effect = ["owned\n", json.dumps(items)]
                with self.assertRaisesRegex(ValueError, "unowned"):
                    provisioning.prepare(arguments)
                opener.assert_not_called()


if __name__ == "__main__":
    unittest.main()
