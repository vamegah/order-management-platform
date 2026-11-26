"""Back up the running database using the last accepted persistent release configuration."""
import argparse, json, re, subprocess
from pathlib import Path


def command(state):
    state = state.resolve()
    current = json.loads((state / "current.json").read_text(encoding="utf-8"))
    if current.get("schemaVersion") != 1 or not re.fullmatch(
        r"omp(?:-[a-z0-9]{1,32})?", current.get("project", "")
    ):
        raise ValueError("Invalid accepted deployment record")
    source = Path(current["sourceDirectory"]).resolve()
    overlay = Path(current["overlay"]).resolve()
    environment = Path(current["envFile"]).resolve()
    if (
        state not in source.parents
        or state not in overlay.parents
        or not environment.is_relative_to(Path("/etc/omp"))
    ):
        raise ValueError("Active release paths escape their managed directories")
    if (
        not (source / "docker-compose.prod.yml").is_file()
        or not overlay.is_file()
        or not environment.is_file()
    ):
        raise ValueError("The accepted release configuration is unavailable")
    return [
        "docker",
        "compose",
        "-p",
        current["project"],
        "--env-file",
        str(environment),
        "-f",
        str(source / "docker-compose.prod.yml"),
        "-f",
        str(overlay),
    ]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state-directory", type=Path, required=True)
    args = parser.parse_args()
    base = command(args.state_directory)
    for action in (("--type=full", "backup"), ("check",)):
        result = subprocess.run(
            [
                *base,
                "exec",
                "-T",
                "--user",
                "70:2000",
                "postgres",
                "/usr/local/bin/omp-pgbackrest",
                *action,
            ],
            capture_output=True,
            timeout=3600,
        )
        if result.returncode:
            raise RuntimeError(
                "Active database backup/check failed; inspect private archive diagnostics"
            )
    print('{"encryptedFullBackup":true,"archiveCheck":true}')


if __name__ == "__main__":
    main()
