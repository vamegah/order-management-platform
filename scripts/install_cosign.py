"""Install cosign from the reviewed container digest, without obsolete detached-signature bootstrap."""
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
    destination = directory / "cosign"
    if destination.exists() or destination.is_symlink():
        raise ValueError("Refusing to replace an installed signing tool")
    image = json.loads((ROOT / "infrastructure/release/images.lock.json").read_text(encoding="utf-8"))["toolsAndBases"]["ghcr.io/sigstore/cosign/cosign:v3.1.5"]
    container = subprocess.check_output([docker, "create", "--label", "com.omp.task=release-tooling", image], text=True).strip()
    try:
        item = json.loads(subprocess.check_output([docker, "inspect", container], text=True))[0]
        if item["Config"].get("Entrypoint", []) != ["/ko-app/cosign"]:
            raise ValueError("Unexpected digest-pinned signing-tool entrypoint")
        subprocess.run([docker, "cp", container + ":/ko-app/cosign", str(destination)], check=True, capture_output=True)
        destination.chmod(0o755)
        subprocess.run([str(destination), "version"], check=True, capture_output=True, timeout=20)
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
