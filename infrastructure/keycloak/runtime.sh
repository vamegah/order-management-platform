#!/bin/sh
set -eu
KC_DB_PASSWORD="$(cat /run/secrets/keycloak_db_password)"
KC_BOOTSTRAP_ADMIN_PASSWORD="$(cat /run/secrets/keycloak_admin_password)"
[ -n "$KC_DB_PASSWORD" ] && [ -n "$KC_BOOTSTRAP_ADMIN_PASSWORD" ] || exit 2
export KC_DB_PASSWORD KC_BOOTSTRAP_ADMIN_PASSWORD
exec /opt/keycloak/bin/kc.sh "$@"
