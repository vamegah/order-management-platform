#!/usr/bin/env bash
# Development-only banking TLS for isolated public API consumer acceptance.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TLS="$ROOT/infrastructure/tls"
PASSWORD="${PUBLIC_API_TLS_PASSWORD:-public-api-acceptance}"
bash "$ROOT/scripts/ci-setup.sh"
BANK="$TLS/legacy-bank-service"
if [[ ! -e "$BANK/tls.key" && ! -e "$BANK/tls.crt" ]]; then
  bash "$ROOT/infrastructure/tls/generate-service-cert.sh" legacy-bank-service --output "$TLS" >/dev/null
fi
openssl verify -CAfile "$TLS/ca/ca.crt" -verify_hostname legacy-bank-service "$BANK/tls.crt" >/dev/null
certificate_key="$(openssl x509 -in "$BANK/tls.crt" -pubkey -noout | openssl pkey -pubin -outform DER | openssl dgst -sha256)"
private_key="$(openssl pkey -in "$BANK/tls.key" -pubout -outform DER | openssl dgst -sha256)"
[[ "$certificate_key" == "$private_key" ]] || { echo 'Banking development identity does not match' >&2; exit 1; }
if [[ ! -e "$BANK/tls.p12" ]]; then
  openssl pkcs12 -export -name legacy-bank-service -inkey "$BANK/tls.key" -in "$BANK/tls.crt" \
    -certfile "$TLS/ca/ca.crt" -out "$BANK/tls.p12" -passout "pass:$PASSWORD"
  chmod 640 "$BANK/tls.p12"
fi
store_key="$(openssl pkcs12 -in "$BANK/tls.p12" -nocerts -nodes -passin "pass:$PASSWORD" 2>/dev/null | openssl pkey -pubout -outform DER | openssl dgst -sha256)"
[[ "$store_key" == "$certificate_key" ]] || { echo 'Restore the matching banking PKCS12 identity' >&2; exit 1; }
if [[ ! -e "$BANK/truststore.p12" ]]; then
  keytool -importcert -noprompt -alias omp-development-ca -storetype PKCS12 \
    -keystore "$BANK/truststore.p12" -storepass "$PASSWORD" -file "$TLS/ca/ca.crt" >/dev/null
fi
trusted_ca="$(keytool -exportcert -rfc -alias omp-development-ca -keystore "$BANK/truststore.p12" \
    -storepass "$PASSWORD" | openssl x509 -noout -fingerprint -sha256)"
expected_ca="$(openssl x509 -in "$TLS/ca/ca.crt" -noout -fingerprint -sha256)"
[[ "$trusted_ca" == "$expected_ca" ]] || { echo 'Banking truststore does not match the development CA' >&2; exit 1; }
echo 'Public API acceptance identities ready; existing identities were preserved.'
