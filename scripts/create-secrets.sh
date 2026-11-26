#!/usr/bin/env bash
# Fresh Linux production setup. Values never enter stdout, argv, or environment.
set -euo pipefail
exec python3 "$(dirname "$0")/prepare_production_secrets.py" "$@"
