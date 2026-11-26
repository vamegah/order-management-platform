#!/bin/sh
# Mounted secret -> child environment, never command arguments or logs.
set -eu
secret_file="${PGBACKREST_CIPHER_FILE:-/run/secrets/pgbackrest_repo_cipher}"
[ -r "$secret_file" ] || { echo 'Unreadable backup cipher file' >&2; exit 1; }
[ "$(wc -c < "$secret_file")" -le 4096 ] || { echo 'Invalid backup cipher file' >&2; exit 1; }
PGBACKREST_REPO1_CIPHER_PASS="$(cat "$secret_file")"
[ -n "$PGBACKREST_REPO1_CIPHER_PASS" ] || { echo 'Empty backup cipher file' >&2; exit 1; }
export PGBACKREST_REPO1_CIPHER_PASS
exec pgbackrest --stanza="${PGBACKREST_STANZA:-omp}" "$@"
