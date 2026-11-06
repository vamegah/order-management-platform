"""Exercise the real executable-jar layer extraction used by the Dockerfiles."""

from pathlib import Path
import subprocess
import sys
import tempfile
from zipfile import ZipFile

from check_verification import NS, reactor_modules


def main():
    root = Path(__file__).resolve().parents[1]
    count = 0
    for module, pom in reactor_modules(root):
        plugins = pom.findall("m:build/m:plugins/m:plugin", NS)
        if not any(plugin.findtext("m:artifactId", namespaces=NS) == "spring-boot-maven-plugin"
                   for plugin in plugins):
            continue
        executable = list((module / "target").glob("*-exec.jar"))
        if len(executable) != 1:
            raise ValueError(f"{module.name}: expected one executable jar; run a clean package")
        archive = executable[0]
        plain = archive.with_name(archive.name.removesuffix("-exec.jar") + ".jar")
        with ZipFile(archive) as jar:
            manifest = jar.read("META-INF/MANIFEST.MF").decode().replace("\r\n ", "").replace("\n ", "")
            if "Main-Class: org.springframework.boot.loader.launch.JarLauncher" not in manifest:
                raise ValueError(f"{module.name}: executable launcher missing")
            if "Start-Class: " not in manifest or "BOOT-INF/layers.idx" not in jar.namelist():
                raise ValueError(f"{module.name}: executable application/layers missing")
            start_class = next(line.removeprefix("Start-Class: ").strip()
                               for line in manifest.splitlines() if line.startswith("Start-Class: "))
        with ZipFile(plain) as jar:
            if start_class.replace(".", "/") + ".class" not in jar.namelist():
                raise ValueError(f"{module.name}: plain reactor jar has no accessible application classes")
        # Layer extraction is disposable and need not enter a synchronized workspace.
        with tempfile.TemporaryDirectory(prefix="omp-packaging-") as output:
            subprocess.run(["java", "-Djarmode=tools", "-jar", str(archive),
                            "extract", "--layers", "--launcher", "--destination", output], check=True, timeout=60)
            layers = Path(output)
            for layer in ("dependencies", "spring-boot-loader", "snapshot-dependencies", "application"):
                if not (layers / layer).is_dir():
                    raise ValueError(f"{module.name}: Docker COPY source layer {layer} missing")
            if not list((layers / "application/BOOT-INF/classes").rglob("*.class")):
                raise ValueError(f"{module.name}: extracted application classes missing")
        print(f"{module.name}: plain jar, executable launcher and all Docker layers verified", flush=True)
        count += 1
    if not count:
        raise ValueError("No executable reactor modules discovered")
    print(f"Verified {count} executable service packages.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        print(f"Packaging verification failed: {error}", file=sys.stderr)
        sys.exit(1)
