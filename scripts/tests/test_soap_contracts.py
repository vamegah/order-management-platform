"""Contract drift must fail before service deployment, including baseline replacement."""

from pathlib import Path
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_soap_contracts import CONTRACTS, MODULES, check, main, normalized


class SoapContractsTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name) / "head"
        self.base = Path(directory.name) / "base"
        source = Path(__file__).resolve().parents[2]
        for root in (self.root, self.base):
            for module in MODULES:
                for relative in CONTRACTS.values():
                    target = root / module / "src/main/resources" / relative
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(source / module / "src/main/resources" / relative, target)
            for name in CONTRACTS:
                target = root / "contracts/soap/v1" / name
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source / "contracts/soap/v1" / name, target)

    def change(self, relative, before, after):
        path = self.root / relative
        text = path.read_text(encoding="utf-8")
        self.assertIn(before, text)
        path.write_text(text.replace(before, after), encoding="utf-8")

    def test_checked_in_contract_and_exact_base_match(self):
        self.assertEqual(check(self.root, self.base), [])

    def test_cosmetic_xml_and_deployment_address_changes_are_allowed(self):
        for module in MODULES:
            self.change(f"{module}/src/main/resources/xsd/banking.xsd", "xs:", "schema:")
            self.change(f"{module}/src/main/resources/xsd/banking.xsd", "xmlns:xs=", "xmlns:schema=")
            self.change(f"{module}/src/main/resources/wsdl/legacy-bank.wsdl",
                        "https://localhost:8443/ws", "https://bank.example.test/ws")
        self.assertEqual(check(self.root), [])

    def test_client_schema_or_operation_drift_is_rejected(self):
        for relative, before, after in (
            ("xsd/banking.xsd", 'type="xs:decimal"', 'type="xs:double"'),
            ("wsdl/legacy-bank.wsdl", 'name="TransferFunds"', 'name="TransferMoney"'),
            ("wsdl/legacy-bank.wsdl", 'soapAction="http://legacybank.example.com/ws/TransferFunds"',
             'soapAction="http://legacybank.example.com/ws/Changed"'),
        ):
            with self.subTest(relative=relative, before=before):
                path = self.root / MODULES[1] / "src/main/resources" / relative
                original = path.read_text(encoding="utf-8")
                path.write_text(original.replace(before, after), encoding="utf-8")
                self.assertTrue(check(self.root))
                path.write_text(original, encoding="utf-8")

    def test_replacing_fixture_and_all_copies_cannot_erase_base_contract(self):
        for path in self.root.rglob("*.xsd"):
            path.write_text(path.read_text().replace('name="amount"', 'name="charge"'))
        for path in self.root.rglob("*.wsdl"):
            path.write_text(path.read_text().replace('name="amount"', 'name="charge"'))
        self.assertEqual(check(self.root), [])
        self.assertTrue(any("base" in error for error in check(self.root, self.base)))

    def test_embedded_schema_must_match_standalone_schema(self):
        self.change("legacy-bank-service/src/main/resources/wsdl/legacy-bank.wsdl",
                    'name="amount"', 'name="charge"')
        self.assertTrue(any("embedded schema" in error for error in check(self.root)))

    def test_previous_wsdl_import_and_inline_schema_have_the_same_contract(self):
        previous = self.base / MODULES[0] / "src/main/resources/wsdl/legacy-bank.wsdl"
        text = previous.read_text()
        start, end = text.index("        <xs:schema"), text.index("        </xs:schema>") + len("        </xs:schema>")
        imported = ('<xsd:schema><xsd:import namespace="http://legacybank.example.com/ws" '
                    'schemaLocation="../xsd/banking.xsd"/></xsd:schema>')
        previous.write_text(text[:start] + imported + text[end:])
        self.assertEqual(normalized(previous), normalized(self.root / MODULES[0] / "src/main/resources/wsdl/legacy-bank.wsdl"))
        for name in CONTRACTS:
            (self.base / "contracts/soap/v1" / name).unlink()
        self.assertEqual(check(self.root, self.base), [])

    def test_missing_or_malformed_files_fail_closed(self):
        fixture = self.root / "contracts/soap/v1/banking.xsd"
        fixture.write_text("broken XML")
        self.assertEqual(main(["--root", str(self.root)]), 1)
        fixture.unlink()
        self.assertEqual(main(["--root", str(self.root)]), 1)


if __name__ == "__main__":
    unittest.main()
