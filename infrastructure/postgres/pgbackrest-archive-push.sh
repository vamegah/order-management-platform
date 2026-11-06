#!/bin/sh
# ==============================================================================
# Order Management Platform — pgBackRest archive-push wrapper
# ------------------------------------------------------------------------------
# FILENAME : infrastructure/postgres/pgbackrest-archive-push.sh
# INSTALLED: /usr/local/bin/pgbackrest-archive-push (via File 9a's Dockerfile)
# INVOKED  : By Postgres's archive_command for every completed WAL segment
#            (File 9's postgresql.prod.conf, section 3)
#
# WHY THIS WRAPPER EXISTS
#   pgBackRest requires the repository's encryption passphrase on the command
#   line (--repo1-cipher-pass=...). It has no built-in mechanism to read the
#   passphrase from a file. Our passphrase lives in a Docker secret at
#   /run/secrets/pgbackrest_repo_cipher.
#
#   This wrapper reads the secret and injects it as --repo1-cipher-pass when
#   invoking pgbackrest. It's the only way to reconcile "secret lives in a
#   file" with "pgBackRest wants it on the command line" without putting the
#   secret in the config file (which is git-committed).
#
# WHY NOT INLINE THE SUBSTITUTION IN archive_command
#   Postgres parses archive_command strictly. These are known to break:
#     archive_command = 'pgbackrest ... --repo1-cipher-pass="$(cat /run/secrets/x)" ...'
#   Specifically:
#     - $() substitution is not reliably supported in all Postgres versions.
#     - Quoting inside archive_command is fragile.
#     - A shell parser bug could cause the passphrase to be logged in
#       Postgres logs, exposing the secret.
#   A wrapper script is:
#     - Robust: standard /bin/sh semantics, no Postgres parser involved.
#     - Auditable: the actual command is one place, not embedded in config.
#     - Testable: can be run manually with a dummy WAL file to verify.
#
# CONTRACT
#   - Input:  one argument, the full path to the WAL segment being archived
#             (Postgres substitutes %p before invoking).
#   - Output: WAL segment is copied (encrypted) to the pgBackRest repository.
#   - Exit:   0 on success; non-zero on failure. Postgres retries on non-zero.
#
#   On non-zero exit, Postgres retains the WAL segment and retries every
#   60 seconds. This gives us a chance to fix transient problems (network,
#   disk) without losing WAL. However, if the wrapper keeps failing, WAL
#   accumulates in pg_wal/ and eventually the disk fills. Phase 7 will add
#   alerting on archive failures.
#
# SECURITY
#   - The passphrase is read into a variable, used in one exec, and never
#     echoed. No logging of the passphrase value.
#   - The secret file is a tmpfs mount (Docker), readable only by root and
#     the process's user. As postgres (uid 999), the wrapper can read it
#     because Docker mounts it with mode 0400 owned by the container user
#     that requested the secret.
#   - set -eu ensures the script fails on unset variables or command errors.
#     Silent failures here would be catastrophic (Postgres would think WAL
#     is archived when it isn't).
#
# REFERENCES
#   pgBackRest archive-push docs:
#     https://pgbackrest.org/command.html#command-archive-push
#   Postgres archive_command docs:
#     https://www.postgresql.org/docs/16/runtime-config-wal.html#GUC-ARCHIVE-COMMAND
#
# ==============================================================================

set -eu

# ------------------------------------------------------------------------------
# Sanity checks
# ------------------------------------------------------------------------------
# Verify we received exactly one argument (the WAL segment path).
# ------------------------------------------------------------------------------
if [ "$#" -ne 1 ]; then
    echo "usage: $0 <wal-segment-path>" >&2
    echo "this script is invoked by Postgres's archive_command; do not run manually" >&2
    exit 1
fi

# ------------------------------------------------------------------------------
# Verify the cipher secret exists and is readable
# ------------------------------------------------------------------------------
# If the secret is missing, every archive call would fail with a cryptic
# pgBackRest error. Better to fail here with a clear message.
# ------------------------------------------------------------------------------
SECRET_FILE="/run/secrets/pgbackrest_repo_cipher"

if [ ! -r "$SECRET_FILE" ]; then
    echo "$0: cannot read secret file: $SECRET_FILE" >&2
    echo "$0: is the pgbackrest_repo_cipher secret mounted?" >&2
    exit 1
fi

# ------------------------------------------------------------------------------
# Invoke pgbackrest
# ------------------------------------------------------------------------------
# --stanza=omp              : the stanza name (matches File 10)
# --repo=1                  : the repository to write to (matches File 10)
# --log-level-console=warn  : quiet on success; only errors/warnings go to
#                             stderr. The shared config sets 'info', but
#                             per-WAL logging at info would be very noisy
#                             (one log line every 16MB of WAL). We override
#                             here so the Postgres container stays quiet.
# --repo1-cipher-pass=...   : the passphrase from the secret
# archive-push              : the pgBackRest command
# "$1"                      : the WAL segment path (Postgres substituted %p)
#
# exec replaces the shell process with pgbackrest — no extra process, and
# the exit code is pgbackrest's exit code directly.
# ------------------------------------------------------------------------------
exec pgbackrest \
    --stanza=omp \
    --repo=1 \
    --log-level-console=warn \
    --repo1-cipher-pass="$(cat "$SECRET_FILE")" \
    archive-push "$1"