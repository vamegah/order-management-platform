"""The deployment gate must reject overlap, including rollback and later overrides."""
import copy
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_stream_topology import check


class StreamTopologyTest(unittest.TestCase):
    def setUp(self):
        service = {"deploy": {"replicas": 1,
                   "update_config": {"order": "stop-first", "parallelism": 1},
                   "rollback_config": {"order": "stop-first", "parallelism": 1}}}
        self.configuration = {"services": {name: copy.deepcopy(service)
                              for name in ("inventory-service", "payment-service")}}

    def test_supported_topology(self):
        self.assertEqual([], check(self.configuration))

    def test_scaling_override_is_rejected_for_either_service(self):
        for name in self.configuration["services"]:
            with self.subTest(service=name):
                configuration = copy.deepcopy(self.configuration)
                configuration["services"][name]["deploy"]["replicas"] = 2
                self.assertTrue(check(configuration))

    def test_update_and_rollback_overlap_are_rejected(self):
        for operation in ("update_config", "rollback_config"):
            for override in ({"order": "start-first", "parallelism": 1},
                             {"order": "stop-first", "parallelism": 2}, {}):
                with self.subTest(operation=operation, policy=override):
                    configuration = copy.deepcopy(self.configuration)
                    configuration["services"]["payment-service"]["deploy"][operation] = override
                    self.assertTrue(check(configuration))

    def test_missing_service_and_global_mode_are_rejected(self):
        self.assertTrue(check({"services": {}}))
        self.configuration["services"]["inventory-service"]["deploy"]["mode"] = "global"
        self.assertTrue(check(self.configuration))
