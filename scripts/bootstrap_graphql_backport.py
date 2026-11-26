"""Build a pinned GraphQL security backport and install it only into the selected local Maven repository."""
import argparse
import hashlib
import json
import os
import re
import zipfile
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def validate(output, locks=ROOT / "infrastructure/dependencies/graphql-java"):
    expected = (locks / "artifacts.sha256").read_text().splitlines()
    names = set()
    for line in expected:
        match = re.fullmatch(r"([0-9a-f]{64})  (graphql-java\.jar|graphql-java\.pom|source\.lock)", line)
        if not match or match[2] in names:
            raise ValueError("Invalid reviewed backport checksum manifest")
        names.add(match[2])
        if hashlib.sha256((output / match[2]).read_bytes()).hexdigest() != match[1]:
            raise ValueError("Backport differs from the independently reviewed artifact")
    if names != {"graphql-java.jar", "graphql-java.pom", "source.lock"}:
        raise ValueError("Incomplete reviewed backport checksum manifest")
    source = (locks / "source.lock").read_bytes()
    if (output / "source.lock").read_bytes() != source:
        raise ValueError("Backport source identity mismatch")
    with zipfile.ZipFile(output / "graphql-java.jar") as archive:
        if archive.read("META-INF/omp/graphql-java-backport.lock") != source:
            raise ValueError("Embedded source identity mismatch")
        properties = archive.read("META-INF/maven/com.google.guava/guava/pom.properties").decode()
        if "version=33.7.2-jre" not in properties.splitlines():
            raise ValueError("Embedded Guava is not the reviewed patched dependency")


def execute(args):
    with tempfile.TemporaryDirectory(prefix="omp-graphql-backport-") as temporary:
        output = Path(temporary) / "artifact"
        subprocess.run([args.docker, "build", "--platform", "linux/amd64", "-f",
                        str(ROOT / "infrastructure/dependencies/graphql-java/Dockerfile"),
                        "--output", "type=local,dest=" + str(output), str(ROOT)], check=True)
        validate(output)
        launcher = ["cmd.exe", "/d", "/c", str(ROOT / "mvnw.cmd")] if os.name == "nt" else ["bash", str(ROOT / "mvnw")]
        command = [*launcher, "--batch-mode", "--no-transfer-progress",
                   "-f", str(output / "graphql-java.pom"),
                   "org.apache.maven.plugins:maven-install-plugin:3.1.2:install-file",
                   "-Dfile=" + str(output / "graphql-java.jar"), "-DpomFile=" + str(output / "graphql-java.pom")]
        if args.repository:
            command.append("-Dmaven.repo.local=" + str(args.repository.resolve()))
        if args.settings:
            command += ["-s", str(args.settings.resolve())]
        subprocess.run(command, cwd=ROOT, check=True)
        print(json.dumps({"scope": "local GraphQL security backport bootstrap", "publishedExternally": False,
                          "artifactSha256": hashlib.sha256((output / "graphql-java.jar").read_bytes()).hexdigest()}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--docker", default="docker")
    parser.add_argument("--repository", type=Path)
    parser.add_argument("--settings", type=Path)
    execute(parser.parse_args())


if __name__ == "__main__":
    main()
