"""Regression coverage for failures hidden by a superficially green SpotBugs check."""
from pathlib import Path
import sys
import tempfile
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_static_analysis import check


class StaticAnalysisEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        # Follow the actual reactor XML namespace used by source discovery.
        (self.root / 'pom.xml').write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion></project>')
        folder = self.root / 'src/main/java'
        folder.mkdir(parents=True)
        (folder / 'Example.java').write_text('class Example {}')
        (self.root / 'target').mkdir()

    def report(self, body='', classes='1'):
        (self.root / 'target/spotbugsXml.xml').write_text(
            f'<BugCollection><FindBugsSummary total_classes="{classes}"/>{body}</BugCollection>')

    def test_complete_clean_report_is_accepted(self):
        self.report()
        self.assertEqual((1, []), check(self.root))

    def test_missing_report_is_rejected(self):
        self.assertTrue(check(self.root)[1])

    def test_findings_errors_and_missing_classes_are_rejected(self):
        for body in ('<BugInstance/>', '<Errors><Error/></Errors>', '<Errors><MissingClass>annotation.Type</MissingClass></Errors>'):
            with self.subTest(body=body):
                self.report(body)
                self.assertTrue(check(self.root)[1])

    def test_empty_analysis_is_rejected(self):
        self.report(classes='0')
        self.assertTrue(check(self.root)[1])

    def test_malformed_report_is_rejected(self):
        (self.root / 'target/spotbugsXml.xml').write_text('<BugCollection>')
        self.assertTrue(check(self.root)[1])


    def test_aggregator_scaffolding_does_not_require_an_unbuilt_report(self):
        (self.root / 'pom.xml').write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><packaging>pom</packaging><modules><module>service</module></modules></project>')
        module = self.root / 'service'
        (module / 'src/main/java').mkdir(parents=True)
        (module / 'src/main/java/Actual.java').write_text('class Actual {}')
        (module / 'pom.xml').write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"/>')
        (module / 'target').mkdir()
        (module / 'target/spotbugsXml.xml').write_text('<BugCollection><FindBugsSummary total_classes="1"/></BugCollection>')
        self.assertEqual((1, []), check(self.root))
