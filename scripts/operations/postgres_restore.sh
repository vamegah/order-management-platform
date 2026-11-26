#!/bin/sh
# Run only in a stopped, separately mounted restore container. Never run in the live database.
set -eu
umask 077
[ "$#" -eq 2 ] || { echo 'usage: postgres_restore.sh /restore/data UTC-target-time' >&2; exit 2; }
restore_target="$1"
case "$restore_target" in /restore/data) ;; *) echo 'Restore target must be /restore/data' >&2; exit 2;; esac
[ ! -L /restore ] && [ ! -L "$restore_target" ] || { echo 'Restore symlinks are forbidden' >&2; exit 2; }
[ -d "$restore_target" ] || mkdir -m 700 "$restore_target"
[ -z "$(ls -A "$restore_target")" ] || { echo 'Restore target must be empty' >&2; exit 2; }
printf '%s\n' "$2" | grep -Eq '^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?\+00(:00)?$' || {
    echo 'An explicit UTC recovery target is required' >&2; exit 2;
}
/usr/local/bin/omp-pgbackrest --pg1-path="$restore_target" --type=time --target="$2" --target-action=pause restore
# Always use the secret-reading wrapper when PostgreSQL requests archived WAL.
printf "\nrestore_command = '/usr/local/bin/omp-pgbackrest --pg1-path=/restore/data archive-get %%f \"%%p\"'\n" >> "$restore_target/postgresql.auto.conf"
