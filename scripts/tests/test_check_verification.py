"""Regression checks for the CI evidence gate, without Maven or Docker."""

from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_verification import check


class VerificationEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.write("pom.xml", '''<project xmlns="http://maven.apache.org/POM/4.0.0">
          <modules><module>service</module></modules></project>''')
        self.write("service/pom.xml", '''<project xmlns="http://maven.apache.org/POM/4.0.0">
          <build><plugins><plugin><artifactId>jacoco-maven-plugin</artifactId>
          <executions><execution><goals><goal>check</goal></goals></execution></executions>
          </plugin></plugins></build></project>''')
        self.source("BehaviorTest")
        self.source("DatabaseIT")
        self.source("JourneyIT", '@Tag("e2e")')

    def write(self, path, content):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")

    def source(self, name, annotation=""):
        self.write(f"service/src/test/java/example/{name}.java",
                   f"package example;\n{annotation}\nclass {name} {{}}")

    def report(self, phase, name, **counts):
        attributes = {"tests": "1", "failures": "0", "errors": "0", "skipped": "0"}
        attributes.update({key: str(value) for key, value in counts.items()})
        text = " ".join(f'{key}="{value}"' for key, value in attributes.items())
        self.write(f"service/target/{phase}-reports/TEST-example.{name}.xml",
                   f'<testsuite name="example.{name}" {text}/>')

    def valid_evidence(self):
        self.report("surefire", "BehaviorTest")
        self.report("failsafe", "DatabaseIT")
        self.write("service/target/site/jacoco/jacoco.xml",
                   '<report><counter type="INSTRUCTION" covered="4" missed="0"/></report>')

    def test_accepts_complete_unit_and_integration_evidence(self):
        self.valid_evidence()
        self.assertEqual(check(self.root), (2, []))

    def test_missing_report_is_failure_even_when_maven_succeeds(self):
        self.valid_evidence()
        (self.root / "service/target/failsafe-reports/TEST-example.DatabaseIT.xml").unlink()
        self.assertTrue(any("DatabaseIT" in error for error in check(self.root)[1]))

    def test_skipped_failed_and_errored_tests_are_rejected(self):
        for result in ("skipped", "errors", "failures"):
            with self.subTest(result=result):
                self.valid_evidence()
                self.report("failsafe", "DatabaseIT", **{result: 1})
                self.assertTrue(any(result in error for error in check(self.root)[1]))

    def test_e2e_profile_requires_e2e_and_untagged_integration_suites(self):
        self.valid_evidence()
        self.assertTrue(any("JourneyIT" in error for error in check(self.root, True)[1]))
        self.report("failsafe", "JourneyIT")
        self.assertEqual(check(self.root, True), (3, []))
        (self.root / "service/target/failsafe-reports/TEST-example.DatabaseIT.xml").unlink()
        self.assertTrue(any("DatabaseIT" in error for error in check(self.root, True)[1]))

    def test_missing_and_empty_coverage_cannot_pass(self):
        self.valid_evidence()
        report = self.root / "service/target/site/jacoco/jacoco.xml"
        report.unlink()
        self.assertTrue(any("missing JaCoCo" in error for error in check(self.root)[1]))
        report.write_text('<report><counter type="INSTRUCTION" covered="0" missed="4"/></report>')
        self.assertTrue(any("no covered" in error for error in check(self.root)[1]))

    def test_recovery_acceptance_is_separate_and_required_when_selected(self):
        self.valid_evidence()
        self.source("ScheduledRecoveryIT", '@Tag("recovery-e2e")')
        self.report("failsafe", "JourneyIT")
        self.assertEqual(check(self.root, True), (3, []))
        self.assertTrue(any("ScheduledRecoveryIT" in error for error in
                            check(self.root, True, include_recovery_e2e=True)[1]))
        self.report("failsafe", "ScheduledRecoveryIT")
        self.assertEqual(check(self.root, True, include_recovery_e2e=True), (4, []))
        self.report("failsafe", "ScheduledRecoveryIT", skipped=1)
        self.assertTrue(any("skipped" in error for error in
                            check(self.root, True, include_recovery_e2e=True)[1]))

    def test_default_verification_does_not_require_recovery_deployment(self):
        self.valid_evidence()
        self.source("ScheduledRecoveryIT", '@Tag("recovery-e2e")')
        self.assertEqual(check(self.root), (2, []))

    def test_nested_test_reports_are_valid(self):
        self.valid_evidence()
        (self.root / "service/target/surefire-reports/TEST-example.BehaviorTest.xml").unlink()
        self.report("surefire", "BehaviorTest$Nested")
        self.assertEqual(check(self.root), (2, []))

    def test_lifecycle_acceptance_is_required_only_when_selected_and_cannot_be_skipped(self):
        self.valid_evidence()
        self.source("CoreLifecycleAcceptanceIT", '@Tag("lifecycle-e2e")')
        self.assertEqual(check(self.root), (2, []))
        self.assertTrue(any("CoreLifecycleAcceptanceIT" in error for error in
                            check(self.root, include_lifecycle_e2e=True)[1]))
        self.report("failsafe", "CoreLifecycleAcceptanceIT")
        self.assertEqual(check(self.root, include_lifecycle_e2e=True), (3, []))
        self.report("failsafe", "CoreLifecycleAcceptanceIT", skipped=1)
        self.assertTrue(any("skipped" in error for error in
                            check(self.root, include_lifecycle_e2e=True)[1]))

    def test_zero_tests_and_wrong_phase_do_not_hide_missing_suite(self):
        self.valid_evidence()
        self.report("failsafe", "DatabaseIT", tests=0)
        self.report("surefire", "DatabaseIT")
        self.assertTrue(any("DatabaseIT" in error for error in check(self.root)[1]))

    def test_aggregator_does_not_compile_its_scaffold_sources(self):
        self.valid_evidence()
        self.write("pom.xml", '''<project xmlns="http://maven.apache.org/POM/4.0.0">
          <packaging>pom</packaging><modules><module>service</module></modules></project>''')
        self.write("src/test/java/ScaffoldTest.java", "package scaffold; class ScaffoldTest {}")
        self.assertEqual(check(self.root), (2, []))


if __name__ == "__main__":
    unittest.main()
