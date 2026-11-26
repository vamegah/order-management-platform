import argparse
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import verify_deployment_rollback as rehearsal


class RollbackIsolationTest(unittest.TestCase):
    def test_an_ordinary_deployment_cannot_be_adopted_as_a_fault_fixture(self):
        for name in ("omp-production", "omp", "", "omp-operations-../../production"):
            with self.subTest(project=name), patch.object(rehearsal.subprocess, "run") as command:
                with self.assertRaisesRegex(ValueError, "Isolated operations project"):
                    rehearsal.execute(argparse.Namespace(project=name, docker="docker", output=Path("unused")))
                command.assert_not_called()


if __name__ == "__main__":
    unittest.main()
