#!/bin/sh
set -eu
[ "$#" -eq 1 ] || { echo 'Expected one WAL segment path' >&2; exit 1; }
exec /usr/local/bin/omp-pgbackrest archive-push "$1"
