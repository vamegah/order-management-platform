#!/usr/bin/env bash
# Invoked only by the disposable SOAP acceptance project. Never touches production identities.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BANK="$ROOT/coverage/soap-fixtures/tls/legacy-bank-service"
ROTATION="$BANK/acceptance-rotation"
SECRET="$ROOT/coverage/soap-fixtures/secrets"
export BANK_STORE_PASSWORD="$(cat "$SECRET/bank/server.ssl.key-store-password")"
export BANK_TRUST_PASSWORD="$(cat "$SECRET/adapter/bank.ws.trust-store-password")"
OWNER="$(stat -c '%u:%g' "$BANK/tls.p12")"
case "${1:-}" in
 prepare)
  [[ ! -e "$ROTATION" ]] || { echo 'Rotation fixture already exists; restore it before rerunning.' >&2; exit 1; }
  umask 027
  bash "$ROOT/infrastructure/tls/generate-ca.sh" --output "$ROTATION" >/dev/null
  bash "$ROOT/infrastructure/tls/generate-service-cert.sh" legacy-bank-service --output "$ROTATION" >/dev/null
  chmod 755 "$ROTATION" "$ROTATION/ca";chmod 644 "$ROTATION/ca/ca.crt"
  keytool -importcert -noprompt -alias soap-rotation-ca -storetype PKCS12 -keystore "$BANK/trust/truststore.p12" -storepass:env BANK_TRUST_PASSWORD -file "$ROTATION/ca/ca.crt" >/dev/null 2>&1
  ;;
 switch)
  [[ -s "$ROTATION/legacy-bank-service/tls.key" ]]
  openssl pkcs12 -export -name legacy-bank-service -inkey "$ROTATION/legacy-bank-service/tls.key" -in "$ROTATION/legacy-bank-service/tls.crt" -certfile "$ROTATION/ca/ca.crt" -out "$BANK/tls.p12" -passout env:BANK_STORE_PASSWORD
  chmod 640 "$BANK/tls.p12";chgrp 1000 "$BANK/tls.p12"
  ;;
 overlap)
  keytool -importcert -noprompt -alias omp-development-ca -storetype PKCS12 -keystore "$BANK/trust/truststore.p12" -storepass:env BANK_TRUST_PASSWORD -file "$ROOT/coverage/soap-fixtures/tls/ca/ca.crt" >/dev/null 2>&1
  ;;
 retire)
  keytool -delete -alias omp-development-ca -keystore "$BANK/trust/truststore.p12" -storepass:env BANK_TRUST_PASSWORD >/dev/null 2>&1
  ;;
 cleanup)
  [[ "$ROTATION" == "$BANK/acceptance-rotation" ]]
  rm -rf -- "$ROTATION"
  ;;
 *) echo 'Expected prepare, switch or retire' >&2;exit 1 ;;
esac
chown "$OWNER" "$BANK/tls.p12" "$BANK/trust/truststore.p12"
chmod 640 "$BANK/tls.p12" "$BANK/trust/truststore.p12"
unset BANK_STORE_PASSWORD BANK_TRUST_PASSWORD
