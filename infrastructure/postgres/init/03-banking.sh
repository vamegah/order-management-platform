#!/usr/bin/env bash
set -euo pipefail
if [[ ! -s /run/secrets/bank-database/ledger-password || ! -s /run/secrets/bank-database/adapter-password ]]; then
 echo "Banking secrets absent; banking roles not provisioned."
else
# Configtree files are mounted read-only; passwords never appear in argv or SQL output.
export BANK_LEDGER_PASSWORD="$(cat /run/secrets/bank-database/ledger-password)"
export BANK_ADAPTER_PASSWORD="$(cat /run/secrets/bank-database/adapter-password)"
[[ -n "$BANK_LEDGER_PASSWORD" && -n "$BANK_ADAPTER_PASSWORD" ]]
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "${POSTGRES_DB:-omp}" <<'SQL'
\set ECHO none
SET log_statement = 'none';
SET log_min_duration_statement = -1;
SET log_min_error_statement = 'panic';
\getenv ledger_password BANK_LEDGER_PASSWORD
\getenv adapter_password BANK_ADAPTER_PASSWORD
SELECT 'CREATE ROLE bank_ledger_svc LOGIN' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname='bank_ledger_svc')\gexec
SELECT 'CREATE ROLE bank_adapter_svc LOGIN' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname='bank_adapter_svc')\gexec
ALTER ROLE bank_ledger_svc WITH PASSWORD :'ledger_password';
ALTER ROLE bank_adapter_svc WITH PASSWORD :'adapter_password';
CREATE SCHEMA IF NOT EXISTS bank_ledger AUTHORIZATION bank_ledger_svc;
CREATE SCHEMA IF NOT EXISTS bank_adapter AUTHORIZATION bank_adapter_svc;
REVOKE ALL ON SCHEMA bank_ledger,bank_adapter FROM PUBLIC;
GRANT CONNECT ON DATABASE omp TO bank_ledger_svc,bank_adapter_svc;
SQL
unset BANK_LEDGER_PASSWORD BANK_ADAPTER_PASSWORD

fi
