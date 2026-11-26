#!/bin/sh
# Initialization only. Existing clusters use the explicit audited rotation procedure.
set -eu
[ "${OMP_PRODUCTION_BOOTSTRAP:-false}" = true ] || exit 0
psql -X --set=ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
\set ECHO none
ALTER ROLE flyway NOLOGIN;
ALTER ROLE reporting_svc NOLOGIN;
SET log_statement = 'none';
SET log_min_duration_statement = -1;
SET log_min_error_statement = 'panic';
SELECT format('ALTER ROLE %I PASSWORD %L',role_name,trim(trailing E'\r\n' FROM pg_read_file('/run/secrets/'||file_name,0,4096)))
FROM (VALUES ('products_svc','products_svc_password'),('customers_svc','customers_svc_password'),
 ('orders_svc','orders_svc_password'),('inventory_svc','inventory_svc_password'),
 ('payments_svc','payments_svc_password'),('notifications_svc','notifications_svc_password'),
 ('keycloak_svc','keycloak_db_password')) AS credentials(role_name,file_name) \gexec
SQL
