#!/usr/bin/env bash
# Create or validate development identities without replacing existing keys.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TLS_DIR="$ROOT/infrastructure/tls"
if [[ $# -gt 0 ]]; then
  if [[ $# -ne 2 || "$1" != --output ]]; then
    echo "Usage: bash scripts/ci-setup.sh [--output directory]" >&2
    exit 1
  fi
  TLS_DIR="$2"
fi
mkdir -p -- "$TLS_DIR"
TLS_DIR="$(cd -- "$TLS_DIR" && pwd)"

validate_pair() {
  local key="$1" certificate="$2" hostname="${3:-}"
  if [[ ! -f "$key" || ! -f "$certificate" ]]; then
    echo "Incomplete identity: $certificate. Restore its matching key/certificate pair." >&2
    return 1
  fi
  openssl x509 -in "$certificate" -noout -checkend 0 >/dev/null
  local certificate_public key_public
  certificate_public="$(openssl x509 -in "$certificate" -pubkey -noout | openssl pkey -pubin -outform DER | openssl dgst -sha256)"
  key_public="$(openssl pkey -in "$key" -pubout -outform DER | openssl dgst -sha256)"
  if [[ "$certificate_public" != "$key_public" ]]; then
    echo "Key/certificate mismatch: $certificate" >&2
    return 1
  fi
  if [[ -n "$hostname" ]]; then
    openssl verify -CAfile "$TLS_DIR/ca/ca.crt" -verify_hostname "$hostname" "$certificate" >/dev/null
  fi
}

if [[ -e "$TLS_DIR/ca/ca.key" || -e "$TLS_DIR/ca/ca.crt" ]]; then
  validate_pair "$TLS_DIR/ca/ca.key" "$TLS_DIR/ca/ca.crt"
else
  bash "$ROOT/infrastructure/tls/generate-ca.sh" --output "$TLS_DIR" >/dev/null
fi
for service in order-service inventory-service payment-service; do
  key="$TLS_DIR/$service/tls.key"
  certificate="$TLS_DIR/$service/tls.crt"
  if [[ -e "$key" || -e "$certificate" ]]; then
    validate_pair "$key" "$certificate" "$service"
  else
    bash "$ROOT/infrastructure/tls/generate-service-cert.sh" "$service" --output "$TLS_DIR" >/dev/null
  fi
done
echo "Development identities ready; existing keys were preserved."
