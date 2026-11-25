#!/usr/bin/env bash
# Disposable development only. Preserve all existing identities and mounted passwords.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TLS="${BANK_TLS_DIR:-$ROOT/infrastructure/tls}"
SECRETS="${BANK_DEVELOPMENT_SECRETS:-$ROOT/infrastructure/secrets/banking}"
umask 027
mkdir -p "$SECRETS/bank" "$SECRETS/adapter" "$SECRETS/postgres"
# Shared public TLS projections retain traversable directories/readable certificates.
# Private keys and the banking configtree keep their separate restrictive permissions.
(umask 022; bash "$ROOT/scripts/ci-setup.sh" --output "$TLS" >/dev/null)
BANK="$TLS/legacy-bank-service"
mkdir -p "$BANK/trust"
if [[ ! -e "$BANK/tls.key" && ! -e "$BANK/tls.crt" ]]; then
 bash "$ROOT/infrastructure/tls/generate-service-cert.sh" legacy-bank-service --output "$TLS" >/dev/null
fi
openssl verify -CAfile "$TLS/ca/ca.crt" -verify_hostname legacy-bank-service "$BANK/tls.crt" >/dev/null
certificate_key="$(openssl x509 -in "$BANK/tls.crt" -pubkey -noout | openssl pkey -pubin -outform DER | openssl dgst -sha256)"
private_key="$(openssl pkey -in "$BANK/tls.key" -pubout -outform DER | openssl dgst -sha256)"
[[ "$certificate_key" == "$private_key" ]] || { echo 'Bank identity mismatch' >&2; exit 1; }
secret() { if [[ ! -e "$1" ]]; then openssl rand -hex 24 | tr -d '\n' > "$1"; fi; [[ -s "$1" ]]; }
secret "$SECRETS/bank/bank.security.password"
secret "$SECRETS/bank/server.ssl.key-store-password"
secret "$SECRETS/bank/spring.datasource.password"
secret "$SECRETS/adapter/spring.datasource.password"
if [[ ! -e "$SECRETS/adapter/bank.ws.password" ]]; then cp "$SECRETS/bank/bank.security.password" "$SECRETS/adapter/bank.ws.password"; fi
cmp -s "$SECRETS/bank/bank.security.password" "$SECRETS/adapter/bank.ws.password" || { echo 'Credentials differ; complete or roll back rotation' >&2; exit 1; }
secret "$SECRETS/adapter/bank.ws.trust-store-password"
PASSWORD="$(cat "$SECRETS/bank/server.ssl.key-store-password")"
export BANK_STORE_PASSWORD="$PASSWORD"
export BANK_TRUST_PASSWORD="$(cat "$SECRETS/adapter/bank.ws.trust-store-password")"
if [[ ! -e "$BANK/tls.p12" ]]; then
 openssl pkcs12 -export -name legacy-bank-service -inkey "$BANK/tls.key" -in "$BANK/tls.crt" -certfile "$TLS/ca/ca.crt" -out "$BANK/tls.p12" -passout env:BANK_STORE_PASSWORD
fi
store_key="$(openssl pkcs12 -in "$BANK/tls.p12" -nocerts -nodes -passin env:BANK_STORE_PASSWORD 2>/dev/null | openssl pkey -pubout -outform DER | openssl dgst -sha256)"
[[ "$store_key" == "$certificate_key" ]] || { echo 'Restore matching bank PKCS12 identity' >&2; exit 1; }
if [[ ! -e "$BANK/trust/truststore.p12" ]]; then
 keytool -importcert -noprompt -alias omp-development-ca -storetype PKCS12 -keystore "$BANK/trust/truststore.p12" -storepass:env BANK_TRUST_PASSWORD -file "$TLS/ca/ca.crt" >/dev/null 2>&1
fi
trusted_ca="$(keytool -exportcert -rfc -alias omp-development-ca -keystore "$BANK/trust/truststore.p12" -storepass:env BANK_TRUST_PASSWORD 2>/dev/null | openssl x509 -noout -fingerprint -sha256)"
expected_ca="$(openssl x509 -in "$TLS/ca/ca.crt" -noout -fingerprint -sha256)"
[[ "$trusted_ca" == "$expected_ca" ]] || { echo 'Bank truststore does not match development CA' >&2; exit 1; }
chmod 750 "$BANK" "$BANK/trust"
chmod 640 "$BANK/tls.p12" "$BANK/trust/truststore.p12" "$SECRETS"/bank/* "$SECRETS"/adapter/*
# Linux service UID/GID 1000 can read mounts; Windows Docker Desktop translates ACLs.
if [[ $(id -u) == 0 ]]; then chgrp -R 1000 "$SECRETS"; chgrp 1000 "$BANK" "$BANK/trust" "$BANK/tls.p12" "$BANK/trust/truststore.p12"; fi
if [[ $(id -u) != 0 ]] && command -v sudo >/dev/null && sudo -n true 2>/dev/null; then
 sudo chgrp -R 1000 "$SECRETS"
 sudo chgrp 1000 "$BANK" "$BANK/trust" "$BANK/tls.p12" "$BANK/trust/truststore.p12"
fi
for pair in 'bank ledger' 'adapter adapter'; do
 read -r source target <<< "$pair"
 if [[ ! -e "$SECRETS/postgres/$target-password" ]]; then cp "$SECRETS/$source/spring.datasource.password" "$SECRETS/postgres/$target-password"; fi
 cmp -s "$SECRETS/$source/spring.datasource.password" "$SECRETS/postgres/$target-password" || { echo 'Database bootstrap secret differs from application secret' >&2; exit 1; }
done
chmod 640 "$SECRETS"/postgres/*
if [[ $(id -u) == 0 ]]; then chgrp -R "${BANK_POSTGRES_GID:-70}" "$SECRETS/postgres";
elif command -v sudo >/dev/null && sudo -n true 2>/dev/null; then sudo chgrp -R "${BANK_POSTGRES_GID:-70}" "$SECRETS/postgres"; fi
# The common parent must be traversable by both service groups; child directories protect contents.
chmod 755 "$SECRETS"
unset PASSWORD BANK_STORE_PASSWORD BANK_TRUST_PASSWORD
echo 'Banking development secrets and trust ready; existing material preserved.'
