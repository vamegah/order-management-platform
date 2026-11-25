#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FIXTURE="$ROOT/coverage/soap-fixtures"
BANK_TLS_DIR="$FIXTURE/tls" BANK_DEVELOPMENT_SECRETS="$FIXTURE/secrets" bash "$ROOT/scripts/setup-banking-development.sh"
