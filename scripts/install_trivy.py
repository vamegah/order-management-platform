"""Install trivy from the reviewed container digest, without unverified binary downloads."""
import argparse
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def install(directory, docker="docker"):
    if not directory.is_absolute() or directory.is_symlink() or directory.resolve() != directory.absolute():
        raise ValueError("An absolute managed tool directory is required")
    directory.mkdir(parents=True, exist_ok=True)
    destination = directory / "trivy"
    if destination.exists() or destination.is_symlink():
        raise ValueError("Refusing to replace an installed scanner")
    image = json.loads((ROOT / "infrastructure/release/images.lock.json").read_text(encoding="utf-8"))["toolsAndBases"]["aquasec/trivy:0.76.2"]
    container = subprocess.check_output([docker, "create", "--label", "com.omp.task=release-tooling", image], text=True).strip()
    try:
        item = json.loads(subprocess.check_output([docker, "inspect", container], text=True))[0]
        if item["Config"].get("Entrypoint", []) != ["trivy"]:
            raise ValueError("Unexpected digest-pinned scanner entrypoint")
        subprocess.run([docker, "cp", container + ":/usr/local/bin/trivy", str(destination)], check=True, capture_output=True)
        destination.chmod(0o755)
        version = subprocess.check_output([str(destination), "--version"], text=True, timeout=20)
        if "Version: 0.76.2" not in version.splitlines():
            raise ValueError("Unexpected digest-pinned scanner version")
    finally:
        subprocess.run([docker, "rm", container], check=True, capture_output=True, timeout=20)
    if os.environ.get("GITHUB_PATH"):
        with open(os.environ["GITHUB_PATH"], "a", encoding="utf-8") as stream:
            stream.write(str(directory) + "\n")
    return {"installed": True, "sourceImage": image}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--docker", default="docker")
    args = parser.parse_args()
    print(json.dumps(install(args.directory, args.docker)))


if __name__ == "__main__":
    main()
