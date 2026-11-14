"""Exercise real issuer operations, including validation before distribution and leaf renewal."""
import hashlib
import os
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from rotate_grpc_certificates import openssl, renew, SERVICES


class CertificateRenewalTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture = tempfile.TemporaryDirectory(prefix='omp-issuer-fixture-')
        cls.root = Path(cls.fixture.name)
        (cls.root / 'ca').mkdir()
        openssl('req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '60', '-subj', '/CN=Issuer fixture',
                '-addext', 'basicConstraints=critical,CA:TRUE', '-keyout', cls.root / 'ca/ca.key', '-out', cls.root / 'ca/ca.crt')
        for service in SERVICES:
            directory = cls.root / service
            directory.mkdir()
            openssl('req', '-new', '-newkey', 'rsa:2048', '-nodes', '-subj', '/CN=' + service,
                    '-keyout', directory / 'tls.key', '-out', directory / 'tls.csr')
            extension = directory / 'extensions'
            extension.write_text('basicConstraints=CA:FALSE\nextendedKeyUsage=serverAuth,clientAuth\nsubjectAltName=DNS:' + service + '\n')
            openssl('x509', '-req', '-in', directory / 'tls.csr', '-CA', cls.root / 'ca/ca.crt',
                    '-CAkey', cls.root / 'ca/ca.key', '-set_serial', '1', '-days', '30', '-extfile', extension, '-out', directory / 'tls.crt')
            extension.unlink()
            (directory / 'tls.csr').unlink()

    @classmethod
    def tearDownClass(cls):
        cls.fixture.cleanup()

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='omp-renewal-case-')
        self.directory = Path(self.temporary.name) / 'issuer'
        shutil.copytree(self.root, self.directory)
        self.addCleanup(self.temporary.cleanup)

    def digests(self):
        return {path.relative_to(self.directory).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
                for path in self.directory.rglob('*') if path.is_file() and path.suffix in ('.key', '.crt')}

    def test_healthy_identities_are_preserved_and_only_public_trust_is_distributed(self):
        before = self.digests()
        self.assertEqual([], renew(self.directory))
        after = self.digests()
        for name, digest in before.items():
            self.assertEqual(digest, after[name])
        self.assertEqual(['ca.crt'], sorted(path.name for path in (self.directory / 'trust').iterdir()))
        self.assertEqual((self.directory / 'ca/ca.crt').read_bytes(), (self.directory / 'trust/ca.crt').read_bytes())

    def test_force_renews_every_leaf_preserves_the_ca_and_publishes_matching_credentials(self):
        before = self.digests()
        self.assertEqual(list(SERVICES), renew(self.directory, force=True))
        after = self.digests()
        for service in SERVICES:
            self.assertNotEqual(before[service + '/tls.key'], after[service + '/tls.key'])
            self.assertNotEqual(before[service + '/tls.crt'], after[service + '/tls.crt'])
            openssl('verify', '-CAfile', self.directory / 'trust/ca.crt', '-verify_hostname', service, self.directory / service / 'tls.crt')
            if os.name != 'nt':
                self.assertEqual(0o640, (self.directory / service / 'tls.key').stat().st_mode & 0o777)
        self.assertEqual(before['ca/ca.key'], after['ca/ca.key'])
        self.assertEqual([], renew(self.directory))
        self.assertFalse(list(self.directory.glob('.renew-*')))

    def test_incomplete_or_mismatched_existing_identity_blocks_all_publication(self):
        target = self.directory / 'payment-service/tls.key'
        target.write_bytes((self.directory / 'order-service/tls.key').read_bytes())
        before = self.digests()
        with self.assertRaises(ValueError):
            renew(self.directory, force=True)
        self.assertEqual(before, self.digests())
        target.unlink()
        with self.assertRaises(ValueError):
            renew(self.directory, force=True)
        self.assertFalse((self.directory / 'trust/ca.crt').exists())

    def test_invalid_lifetime_and_untrusted_distribution_fail_closed(self):
        for before, lifetime in ((7, 0), (-1, 30), (30, 30)):
            with self.subTest(before=before, lifetime=lifetime), self.assertRaises(ValueError):
                renew(self.directory, before, lifetime)
        with self.assertRaises(RuntimeError):
            renew(self.directory, force=True, trust_bundle=self.directory / 'order-service/tls.crt')
        self.assertFalse((self.directory / 'trust/ca.crt').exists())
