#!/usr/bin/env bash
# Verify repeat setup preserves identities and refuses damaged existing material.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
FIXTURE="$(mktemp -d)"
trap 'rm -rf -- "$FIXTURE"' EXIT
bash "$ROOT/scripts/ci-setup.sh" --output "$FIXTURE" >/dev/null
before="$(find "$FIXTURE" -type f \( -name '*.key' -o -name '*.crt' \) -exec sha256sum {} \; | sort)"
bash "$ROOT/scripts/ci-setup.sh" --output "$FIXTURE" >/dev/null
after="$(find "$FIXTURE" -type f \( -name '*.key' -o -name '*.crt' \) -exec sha256sum {} \; | sort)"
[[ "$before" == "$after" ]]
cp "$FIXTURE/payment-service/tls.key" "$FIXTURE/order-service/tls.key"
if bash "$ROOT/scripts/ci-setup.sh" --output "$FIXTURE" > "$FIXTURE/rejected.log" 2>&1; then
  echo 'Mismatched identity was accepted' >&2
  exit 1
fi
grep -q 'Key/certificate mismatch' "$FIXTURE/rejected.log"
rm "$FIXTURE/order-service/tls.key"
if bash "$ROOT/scripts/ci-setup.sh" --output "$FIXTURE" > "$FIXTURE/rejected.log" 2>&1; then
  echo 'Incomplete identity was replaced' >&2
  exit 1
fi
grep -q 'Incomplete identity' "$FIXTURE/rejected.log"
[[ ! -e "$FIXTURE/order-service/tls.key" ]]
if bash "$ROOT/infrastructure/tls/generate-service-cert.sh" ../escape --output "$FIXTURE" > "$FIXTURE/rejected.log" 2>&1; then
  echo 'Invalid service identity was accepted' >&2
  exit 1
fi
grep -q 'must be a DNS label' "$FIXTURE/rejected.log"
echo 'Verified repeat setup preserves keys and rejects mismatched/incomplete identities and invalid service names.'
