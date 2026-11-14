import copy
from pathlib import Path
import sys
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_grpc_transport import check


class GrpcTransportPolicyTest(unittest.TestCase):
    def setUp(self):
        service = {'environment': {'SPRING_PROFILES_ACTIVE': 'prod'},
                   'volumes': [{'target': path, 'source': '/identities/' + path.rsplit('/', 1)[1], 'read_only': True}
                               for path in ('/etc/tls/identity', '/etc/tls/trust')],
                   'deploy': {'replicas': 1, 'update_config': {'order': 'stop-first', 'parallelism': 1},
                              'rollback_config': {'order': 'stop-first', 'parallelism': 1}}}
        self.config = {'services': {name: copy.deepcopy(service) for name in ('order-service', 'inventory-service', 'payment-service')}}

    def test_accepts_rotating_mutual_tls(self):
        self.assertEqual([], check(self.config))

    def test_rejects_plaintext_anonymous_or_nonrotating_overrides(self):
        for variable, value in (('GRPC_MTLS_ENABLED', 'false'), ('GRPC_SERVER_SECURITY_ENABLED', 'false'), ('OMP_GRPC_TLS_RELOAD_ENABLED', 'false'), ('GRPC_CERTIFICATE_RELOAD_ENABLED', 'false'),
                                ('GRPC_REFLECTION_ENABLED', 'true'), ('GRPC_SERVER_ENABLE_REFLECTION', 'true'),
                                ('GRPC_SERVER_SECURITY_CLIENT_AUTH', 'NONE'),
                                ('GRPC_CLIENT_INVENTORY_SERVICE_NEGOTIATION_TYPE', 'PLAINTEXT'),
                                ('GRPC_CLIENT_PAYMENT_SERVICE_SECURITY_CLIENT_AUTH_ENABLED', 'false')):
            with self.subTest(variable=variable):
                config = copy.deepcopy(self.config)
                config['services']['order-service']['environment'][variable] = value
                self.assertTrue(check(config))

    def test_rejects_pinned_files_writable_mounts_and_ca_private_key(self):
        for mount in ({'target': '/etc/tls/tls.crt'}, {'target': '/etc/tls/identity', 'read_only': False},
                      {'target': '/issuer', 'source': '/private/tls/ca'}, {'target': '/private-key', 'source': '/issuer/ca.key'}):
            with self.subTest(mount=mount):
                config = copy.deepcopy(self.config)
                config['services']['payment-service']['volumes'].append(mount)
                self.assertTrue(check(config))

    def test_requires_single_replica_replacement_and_production_profiles(self):
        self.config['services']['inventory-service']['deploy']['replicas'] = 2
        self.assertTrue(check(self.config))
        self.config['services']['order-service']['environment']['SPRING_PROFILES_ACTIVE'] = 'local'
        self.assertTrue(check(self.config))
