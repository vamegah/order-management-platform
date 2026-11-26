#!/bin/sh
set -eu
[ "${OMP_PRODUCTION_BOOTSTRAP:-false}" = true ] || exit 0
/usr/local/bin/omp-pgbackrest stanza-create
