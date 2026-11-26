"""Only a protected tag on an already verified main commit may publish a release."""
import fnmatch, json, os, re, subprocess
from urllib.request import Request, urlopen

REPO = "vamegah/order-management-platform"


def main():
    if not os.environ.get("GH_TOKEN"):
        raise ValueError(
            "Configure a read-only release policy token; strict branch protection checks are mandatory"
        )
    revision = os.environ["GITHUB_SHA"]
    tag = os.environ["GITHUB_REF_NAME"]
    if not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+(?:-[a-z0-9.]+)?", tag):
        raise ValueError("Invalid semantic release tag")
    if (
        os.environ["GITHUB_REPOSITORY"] != REPO
        or os.environ["GITHUB_REF"] != "refs/tags/" + tag
    ):
        raise ValueError("Unexpected release source")
    subprocess.run(
        ["git", "merge-base", "--is-ancestor", revision, "origin/main"], check=True
    )
    headers = {
        "Authorization": "Bearer " + os.environ["GH_TOKEN"],
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
    }

    def get(path):
        with urlopen(
            Request("https://api.github.com/repos/" + REPO + path, headers=headers),
            timeout=30,
        ) as response:
            return json.load(response)

    protection = get("/branches/main/protection")
    if not protection.get("enforce_admins", {}).get("enabled") or not protection.get(
        "required_status_checks", {}
    ).get("strict"):
        raise ValueError("Main branch verification/freshness protection is missing")
    if "quality-gate" not in protection["required_status_checks"].get("contexts", []):
        raise ValueError("Required quality gate is missing")
    runs = get(
        "/actions/workflows/verify.yml/runs?branch=main&event=push&head_sha=" + revision
    )["workflow_runs"]
    accepted = [
        r
        for r in runs
        if r["head_sha"] == revision
        and r["status"] == "completed"
        and r["conclusion"] == "success"
    ]
    if not accepted:
        raise ValueError("Source revision has no complete successful main verification")
    # An active tag ruleset prevents replacing the release identity after signing.
    rules = get("/rulesets?includes_parents=true")

    def immutable(rule):
        if rule.get("target") != "tag" or rule.get("enforcement") != "active":
            return False
        detail = get("/rulesets/" + str(rule["id"]))
        refs = detail.get("conditions", {}).get("ref_name", {})
        include = refs.get("include", [])
        exclude = refs.get("exclude", [])
        selected = any(
            pattern == "~ALL" or fnmatch.fnmatchcase("refs/tags/" + tag, pattern)
            for pattern in include
        )
        excluded = any(
            pattern == "~ALL" or fnmatch.fnmatchcase("refs/tags/" + tag, pattern)
            for pattern in exclude
        )
        protected = {item.get("type") for item in detail.get("rules", [])} >= {
            "update",
            "deletion",
        }
        return (
            selected and not excluded and protected and not detail.get("bypass_actors")
        )

    if not any(immutable(r) for r in rules):
        raise ValueError(
            "The selected release tag needs enforced update/deletion protection without bypass"
        )
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
        output.write("run=" + str(accepted[0]["id"]) + "\n")
    print(
        json.dumps(
            {
                "verifiedSource": revision,
                "verificationRun": accepted[0]["id"],
                "release": tag,
            }
        )
    )


if __name__ == "__main__":
    main()
