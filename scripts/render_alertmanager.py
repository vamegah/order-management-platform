"""Render Alertmanager from explicit mounted inputs; refuse insecure production destinations."""
import argparse, json, os, stat
from pathlib import Path
from urllib.parse import urlsplit


def render(url, credential_path="/run/secrets/alert_receiver_token"):
    parsed = urlsplit(url)
    if (
        parsed.scheme != "https"
        or not parsed.hostname
        or parsed.username
        or parsed.password
        or parsed.fragment
        or parsed.query
    ):
        raise ValueError(
            "Receiver must be an HTTPS URL without embedded credentials, query or fragment"
        )
    if len(url) > 2048 or any(c.isspace() for c in url):
        raise ValueError("Invalid receiver URL")
    return {
        "global": {"resolve_timeout": "1m"},
        "route": {
            "receiver": "operations",
            "group_by": ["env", "service", "alertname"],
            "group_wait": "5s",
            "group_interval": "15s",
            "repeat_interval": "4h",
            "routes": [
                {
                    "receiver": "critical",
                    "matchers": ['severity="critical"'],
                    "group_wait": "0s",
                    "repeat_interval": "1h",
                }
            ],
        },
        "inhibit_rules": [
            {
                "source_matchers": ['severity="critical"'],
                "target_matchers": ['severity="warning"'],
                "equal": ["env", "service", "alertname"],
            }
        ],
        "receivers": [
            {
                "name": name,
                "webhook_configs": [
                    {
                        "url": url,
                        "send_resolved": True,
                        "max_alerts": 50,
                        "http_config": {
                            "follow_redirects": False,
                            "authorization": {"credentials_file": credential_path},
                            "tls_config": {"min_version": "TLS12"},
                        },
                    }
                ],
            }
            for name in ("operations", "critical")
        ],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url-file", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    content = json.dumps(
        render(args.url_file.read_text(encoding="utf-8").strip()), indent=2
    ).encode()
    # JSON is valid YAML. O_EXCL prevents accidental overwrite of live configuration.
    fd = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o640)
    if os.name == "posix":
        os.fchown(fd, -1, 2000)
    with os.fdopen(fd, "wb") as target:
        target.write(content)
    print(json.dumps({"rendered": True, "credentialValuesIncluded": False}))


if __name__ == "__main__":
    main()
