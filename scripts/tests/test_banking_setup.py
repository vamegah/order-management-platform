"""Exercise real development secret/TLS setup; OpenSSL/keytool are required by CI."""
from pathlib import Path
import hashlib, os, subprocess, tempfile, unittest
ROOT=Path(__file__).resolve().parents[2]
class BankingSetupTest(unittest.TestCase):
    def setup_fixture(self,directory):
        env={**os.environ,'BANK_TLS_DIR':str(directory/'tls'),'BANK_DEVELOPMENT_SECRETS':str(directory/'secrets')}
        return subprocess.run(['bash',str(ROOT/'scripts/setup-banking-development.sh')],env=env,capture_output=True)
    def hashes(self,directory):return {str(p.relative_to(directory)):hashlib.sha256(p.read_bytes()).hexdigest() for p in directory.rglob('*') if p.is_file()}
    def test_repeat_setup_preserves_credentials_and_certificates(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory=Path(temporary)
            first=self.setup_fixture(directory);self.assertEqual(first.returncode,0,first.stderr.decode(errors='replace'))
            before=self.hashes(directory);second=self.setup_fixture(directory)
            self.assertEqual(second.returncode,0,second.stderr.decode(errors='replace'));self.assertEqual(before,self.hashes(directory))
            secrets=directory/'secrets'
            self.assertEqual((secrets/'bank/bank.security.password').read_bytes(),(secrets/'adapter/bank.ws.password').read_bytes())
            self.assertNotEqual((secrets/'bank/server.ssl.key-store-password').read_bytes(),(secrets/'adapter/bank.ws.trust-store-password').read_bytes())
            for file in secrets.rglob('*'):
                if file.is_file():
                    self.assertTrue(file.stat().st_size>0);self.assertEqual(file.stat().st_mode & 0o007,0)
                    self.assertNotIn(file.read_bytes(),first.stdout+first.stderr+second.stdout+second.stderr)
            self.assertTrue((directory/'tls/legacy-bank-service/trust/truststore.p12').is_file())
            # Banking setup also prepares the shared gRPC projections. A different
            # application UID must traverse the directory and read its public cert.
            for service in ('order-service','inventory-service','payment-service'):
                projection=directory/'tls'/service
                self.assertTrue(projection.stat().st_mode & 0o001)
                self.assertTrue((projection/'tls.crt').stat().st_mode & 0o004)
                self.assertEqual((projection/'tls.key').stat().st_mode & 0o007,0)
    def test_partial_or_mismatched_credentials_are_not_silently_replaced(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory=Path(temporary);self.assertEqual(self.setup_fixture(directory).returncode,0)
            password=directory/'secrets/adapter/bank.ws.password';password.write_text('different-fixture-value',encoding='utf-8')
            before=self.hashes(directory);rejected=self.setup_fixture(directory)
            self.assertNotEqual(rejected.returncode,0);self.assertEqual(before,self.hashes(directory))
            self.assertNotIn(b'different-fixture-value',rejected.stdout+rejected.stderr)
if __name__=='__main__':unittest.main()
