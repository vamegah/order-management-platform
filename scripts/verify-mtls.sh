#!/usr/bin/env bash
# ==============================================================================
# verify-mtls.sh — Verify a service certificate against the CA
# ------------------------------------------------------------------------------
# Diagnostic tool. Prints the certificate's subject, issuer, validity, SANs,
# and verifies it against the CA.
#
# USAGE
#   ./scripts/verify-mtls.sh <service-name> [--tls-dir <dir>]
#
# EXAMPLE
#   ./scripts/verify-mtls.sh inventory-service
# ==============================================================================

set -euo pipefail

SERVICE="$1"
TLS_DIR="infrastructure/tls"

shift
while [[ $# -gt 0 ]]; do
  case "$1" in
    --tls-dir)
      TLS_DIR="$2"
      shift 2
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 1
      ;;
  esac
done

CERT="${TLS_DIR}/${SERVICE}/tls.crt"
CA="${TLS_DIR}/ca/ca.crt"

if [[ ! -f "$CERT" ]]; then
  echo "ERROR: Certificate not found: ${CERT}" >&2
  exit 1
fi

if [[ ! -f "$CA" ]]; then
  echo "ERROR: CA certificate not found: ${CA}" >&2
  exit 1
fi

echo "Certificate: ${CERT}"
echo ""
echo "Subject:"
openssl x509 -in "$CERT" -noout -subject
echo ""
echo "Issuer:"
openssl x509 -in "$CERT" -noout -issuer
echo ""
echo "Validity:"
openssl x509 -in "$CERT" -noout -dates
echo ""
echo "SANs:"
openssl x509 -in "$CERT" -noout -ext subjectAltName
echo ""
echo "Verifying against CA..."
openssl verify -CAfile "$CA" "$CERT"
echo ""
echo "OK."