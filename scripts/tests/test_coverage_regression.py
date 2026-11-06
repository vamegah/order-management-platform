"""Coverage gate failure cases, using isolated reactor/report fixtures."""

from contextlib import redirect_stderr, redirect_stdout
import copy
from io import StringIO
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_coverage_regression import SCHEMA, compare, instruction_counts, main, snapshot


class CoverageRegressionTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.write("pom.xml", '''<project xmlns="http://maven.apache.org/POM/4.0.0">
            <packaging>pom</packaging><modules><module>service</module></modules></project>''')
        self.write("service/pom.xml", '<project xmlns="http://maven.apache.org/POM/4.0.0"/>')
        self.write("service/src/main/java/example/Service.java", "package example; class Service {}")
        self.write("service/src/test/java/example/BehaviorTest.java",
                   "package example; class BehaviorTest {}")
        self.write("service/src/test/java/example/DatabaseIT.java",
                   "package example; class DatabaseIT {}")
        self.write("service/target/surefire-reports/TEST-example.BehaviorTest.xml",
                   '<testsuite name="example.BehaviorTest" tests="3" failures="0" errors="0" skipped="0"/>')
        self.report = self.root / "service/target/site/jacoco/jacoco.xml"
        self.coverage()

    def write(self, path, text):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")
        return target

    def coverage(self, covered=8, missed=2, extra=""):
        self.write("service/target/site/jacoco/jacoco.xml",
                   f'<report><package name="example"><class name="example/Service">'
                   f'<counter type="INSTRUCTION" covered="{covered}" missed="{missed}"/>'
                   f'</class>{extra}</package></report>')

    def evidence(self, modules=None):
        return {"schema": SCHEMA, "revision": "fixture", "tests": 3,
                "modules": modules or {"service": {"covered": 8, "missed": 2}}}

    def test_snapshot_requires_unit_suites_and_not_container_suites(self):
        result = snapshot(self.root, "commit-sha")
        self.assertEqual(result["tests"], 3)
        self.assertEqual(result["revision"], "commit-sha")
        self.assertEqual(result["modules"], {"service": {"covered": 8, "missed": 2}})
        self.write("service/target/failsafe-reports/TEST-example.DatabaseIT.xml",
                   '<testsuite name="example.DatabaseIT" tests="9" errors="9"/>')
        self.assertEqual(snapshot(self.root, "commit-sha"), result)

    def test_missing_or_skipped_unit_suite_rejects_snapshot(self):
        path = self.root / "service/target/surefire-reports/TEST-example.BehaviorTest.xml"
        path.unlink()
        with self.assertRaisesRegex(ValueError, "missing/nonexecuted"):
            snapshot(self.root, "sha")
        path.write_text('<testsuite name="example.BehaviorTest" tests="3" skipped="1"/>')
        with self.assertRaisesRegex(ValueError, "skipped"):
            snapshot(self.root, "sha")

    def test_missing_module_report_is_not_a_bootstrap_success(self):
        self.report.unlink()
        with self.assertRaises(OSError):
            snapshot(self.root, "sha")

    def test_report_exclusion_cannot_hide_an_uncovered_source_class(self):
        self.write("service/src/main/java/example/config/Security.java",
                   "package example.config; public class Security {}")
        with self.assertRaisesRegex(ValueError, "handwritten classes missing.*example/config/Security"):
            snapshot(self.root, "sha")

    def test_empty_interfaces_do_not_require_executable_instruction_counters(self):
        self.write("service/src/main/java/example/Port.java", "package example; interface Port {}")
        self.assertIn("service", snapshot(self.root, "sha")["modules"])

    def test_pom_aggregator_scaffolds_are_not_compiled_production_modules(self):
        self.write("src/main/java/scaffold/Scaffold.java", "package scaffold; class Scaffold {}")
        result = snapshot(self.root, "sha")
        self.assertEqual(set(result["modules"]), {"service"})

    def test_generated_code_is_filtered_but_handwritten_config_is_counted(self):
        self.coverage(extra='''
            <class name="com/omp/bank/soap/Generated"><counter type="INSTRUCTION" covered="0" missed="999"/></class>
            <class name="com/example/omp/proto/Generated"><counter type="INSTRUCTION" covered="0" missed="999"/></class>
            <class name="example/config/Security"><counter type="INSTRUCTION" covered="1" missed="9"/></class>''')
        self.assertEqual(instruction_counts(self.report), {"covered": 9, "missed": 11})

    def test_invalid_report_counters_fail_closed(self):
        for covered, missed in ((0, 0), (0, 10), (-1, 10), (1, -1), ("broken", 1)):
            with self.subTest(covered=covered, missed=missed):
                self.coverage(covered, missed)
                with self.assertRaises(ValueError):
                    instruction_counts(self.report)

    def test_duplicate_classes_and_counters_cannot_inflate_coverage(self):
        self.coverage(extra='<class name="example/Service"/>')
        with self.assertRaisesRegex(ValueError, "duplicate class"):
            instruction_counts(self.report)
        self.report.write_text('''<report><package><class name="example/Service">
            <counter type="INSTRUCTION" covered="8" missed="2"/>
            <counter type="INSTRUCTION" covered="8" missed="2"/>
            </class></package></report>''')
        with self.assertRaisesRegex(ValueError, "duplicate instruction"):
            instruction_counts(self.report)

    def test_equal_and_increased_ratios_pass_without_matching_instruction_counts(self):
        for covered, missed in ((16, 4), (9, 1)):
            head = self.evidence({"service": {"covered": covered, "missed": missed}})
            self.assertEqual(compare(self.evidence(), head)[1], [])

    def test_unrounded_regression_is_rejected(self):
        base = self.evidence({"service": {"covered": 8_000_000, "missed": 2_000_000}})
        head = self.evidence({"service": {"covered": 7_999_999, "missed": 2_000_001}})
        self.assertIn("regressed", compare(base, head)[1][0])

    def test_improvement_elsewhere_cannot_mask_a_module_regression(self):
        base = self.evidence({"one": {"covered": 8, "missed": 2},
                              "two": {"covered": 1, "missed": 9}})
        head = self.evidence({"one": {"covered": 7, "missed": 3},
                              "two": {"covered": 10, "missed": 0}})
        self.assertEqual(compare(base, head)[1], ["one: unit instruction coverage regressed"])

    def test_missing_baseline_module_fails_and_new_module_needs_80_percent(self):
        base = self.evidence()
        missing = self.evidence({"different": {"covered": 8, "missed": 2}})
        self.assertTrue(any("missing" in error for error in compare(base, missing)[1]))
        head = copy.deepcopy(base)
        head["modules"]["new"] = {"covered": 7, "missed": 3}
        self.assertTrue(any("new module" in error for error in compare(base, head)[1]))
        head["modules"]["new"] = {"covered": 8, "missed": 2}
        self.assertEqual(compare(base, head)[1], [])

    def test_invalid_snapshot_metadata_and_counters_fail_closed(self):
        invalid = []
        for key, value in (("schema", "other-policy"), ("tests", 0), ("tests", True),
                           ("revision", ""), ("modules", {})):
            data = self.evidence()
            data[key] = value
            invalid.append(data)
        for counts in ({"covered": True, "missed": 0}, {"covered": 0, "missed": 1},
                       {"covered": 1, "missed": -1}, {"covered": 1},
                       {"covered": 1.5, "missed": 0}):
            invalid.append(self.evidence({"service": counts}))
        for data in invalid:
            with self.subTest(data=data):
                with self.assertRaises(ValueError):
                    compare(self.evidence(), data)

    def test_invalid_module_paths_fail_closed(self):
        for path in ("../service", "/absolute", "C:/service", "bad\\module", ""):
            with self.subTest(path=path):
                with self.assertRaises(ValueError):
                    compare(self.evidence(), self.evidence({path: {"covered": 8, "missed": 2}}))

    def test_cli_writes_snapshot_and_reports_regression_exit_status(self):
        head = self.root / "head.json"
        with redirect_stdout(StringIO()), redirect_stderr(StringIO()):
            self.assertEqual(main(["snapshot", "--root", str(self.root), "--revision", "head-sha",
                                   "--output", str(head)]), 0)
        base = self.write("base.json", json.dumps(self.evidence()))
        output = StringIO()
        with redirect_stdout(output):
            self.assertEqual(main(["compare", "--base", str(base), "--head", str(head)]), 0)
        self.assertIn("PASS", output.getvalue())
        data = json.loads(head.read_text())
        data["modules"]["service"] = {"covered": 7, "missed": 3}
        head.write_text(json.dumps(data))
        with redirect_stdout(StringIO()), redirect_stderr(StringIO()):
            self.assertEqual(main(["compare", "--base", str(base), "--head", str(head)]), 1)

    def test_cli_rejects_missing_and_malformed_evidence(self):
        head = self.write("head.json", "not json")
        base = self.write("base.json", json.dumps(self.evidence()))
        with redirect_stdout(StringIO()), redirect_stderr(StringIO()):
            self.assertEqual(main(["compare", "--base", str(base), "--head", str(head)]), 1)
            base.unlink()
            self.assertEqual(main(["compare", "--base", str(base), "--head", str(head)]), 1)


if __name__ == "__main__":
    unittest.main()
