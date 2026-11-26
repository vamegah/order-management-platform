"""Minimal source and hardware identity for executed isolated acceptance reports."""
import hashlib
import json
import subprocess


def evidence(docker, root):
    def git(*args):
        return subprocess.check_output(
            ["git", "-c", "safe.directory=" + root.as_posix(), *args], cwd=root
        )

    names = (
        git("ls-files", "--cached", "--others", "--exclude-standard", "-z")
        .decode("utf-8")
        .split("\0")
    )
    manifest = {
        name: hashlib.sha256((root / name).read_bytes()).hexdigest()
        for name in names
        if name and (root / name).is_file()
    }
    info = json.loads(
        subprocess.check_output([docker, "info", "--format", "{{json .}}"], text=True)
    )
    return {
        "sourceRevision": git("rev-parse", "HEAD").decode().strip(),
        "sourceDirty": bool(git("status", "--porcelain").strip()),
        "sourceTreeSha256": hashlib.sha256(
            json.dumps(manifest, sort_keys=True).encode()
        ).hexdigest(),
        "hardware": {
            key: info.get(key)
            for key in ("NCPU", "MemTotal", "OSType", "Architecture", "ServerVersion")
        },
        "scope": "isolated operations acceptance",
    }
