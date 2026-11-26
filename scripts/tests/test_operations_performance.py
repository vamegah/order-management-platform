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


if __name__ == "__main__":
    unittest.main()
