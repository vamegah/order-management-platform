from pathlib import Path
import sys
import tempfile
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_junit import check


class JunitEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.report = Path(self.directory.name) / 'results.xml'

    def test_actual_successful_cases_are_counted(self):
        self.report.write_text('<testsuites><testsuite><testcase/><testcase/></testsuite></testsuites>')
        self.assertEqual((2, []), check(self.report, 2))

    def test_declared_suite_totals_cannot_replace_executed_cases(self):
        self.report.write_text('<testsuite tests="100"/>')
        self.assertTrue(check(self.report)[1])

    def test_failure_error_and_skip_are_rejected(self):
        for tag in ('failure', 'error', 'skipped'):
            with self.subTest(tag=tag):
                self.report.write_text(f'<testsuite><testcase><{tag}/></testcase></testsuite>')
                self.assertTrue(check(self.report)[1])

    def test_missing_and_malformed_reports_are_rejected(self):
        self.assertTrue(check(self.report)[1])
        self.report.write_text('<testsuite>')
        self.assertTrue(check(self.report)[1])

    def test_minimum_required_scope_cannot_disappear(self):
        self.report.write_text('<testsuite><testcase/></testsuite>')
        self.assertTrue(check(self.report, 2)[1])
