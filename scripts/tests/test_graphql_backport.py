import hashlib
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from bootstrap_graphql_backport import validate


class BackportArtifactTest(unittest.TestCase):
    def prepare(self, directory, version="33.7.2-jre"):
        locks = directory / "locks"
        output = directory / "output"
        locks.mkdir()
        output.mkdir()
        source = b"SOURCE_REVISION=reviewed-fixture\n"
        (locks / "source.lock").write_bytes(source)
        (output / "source.lock").write_bytes(source)
        (output / "graphql-java.pom").write_bytes(b"reviewed-pom")
        with zipfile.ZipFile(output / "graphql-java.jar", "w") as archive:
            archive.writestr("META-INF/omp/graphql-java-backport.lock", source)
            archive.writestr("META-INF/maven/com.google.guava/guava/pom.properties", "version=" + version)
        (locks / "artifacts.sha256").write_text("\n".join(
            hashlib.sha256((output / name).read_bytes()).hexdigest() + "  " + name
            for name in ("graphql-java.jar", "graphql-java.pom", "source.lock")))
        return output, locks

    def test_changed_artifact_and_incomplete_manifest_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            output, locks = self.prepare(Path(temporary))
            validate(output, locks)
            (output / "graphql-java.jar").write_bytes(b"substituted-runtime")
            with self.assertRaisesRegex(ValueError, "independently reviewed"):
                validate(output, locks)
            (locks / "artifacts.sha256").write_text("")
            with self.assertRaisesRegex(ValueError, "Incomplete"):
                validate(output, locks)

    def test_old_embedded_guava_is_rejected_even_with_a_matching_output_hash(self):
        with tempfile.TemporaryDirectory() as temporary:
            output, locks = self.prepare(Path(temporary), version="32.1.2-jre")
            with self.assertRaisesRegex(ValueError, "Embedded Guava"):
                validate(output, locks)


if __name__ == "__main__":
    unittest.main()
