#!/usr/bin/env bash
# ==============================================================================
# generate-ca.sh — Generate a root CA for the platform's internal mTLS
# ------------------------------------------------------------------------------
# USAGE
#   ./generate-ca.sh [--output <dir>]
#
#   Default output directory: the current directory (infrastructure/tls/).
#   For production: --output /etc/omp/tls
#
# WHAT IT GENERATES
#   ca.key — the CA's private key (4096-bit RSA)
#   ca.crt — the CA's self-signed certificate (valid 10 years)
#
# SAFETY
#   Refuses to overwrite an existing CA. To rotate the CA, delete ca.key and
#   ca.crt manually (and regenerate all service certificates).
#
# WHAT TO DO WITH ca.key
#   Save it in a password manager or offline backup. If it's lost, all
#   service certificates become unmanageable (can't sign new ones, can't
#   rotate). Do NOT commit it. Do NOT mount it into containers.
# ==============================================================================

set -euo pipefail

OUTPUT_DIR="$(pwd)"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --output)
      OUTPUT_DIR="$2"
      shift 2
      ;;
    *)
      echo "Unknown argument: $1" >&2
      echo "Usage: $0 [--output <dir>]" >&2
      exit 1
      ;;
  esac
done

CA_DIR="${OUTPUT_DIR}/ca"
CA_KEY="${CA_DIR}/ca.key"
CA_CERT="${CA_DIR}/ca.crt"

# ------------------------------------------------------------------------------
# Pre-flight checks
# ------------------------------------------------------------------------------

if ! command -v openssl >/dev/null 2>&1; then
  echo "ERROR: openssl is required but not found." >&2
  exit 1
fi

if [[ -f "$CA_KEY" ]] || [[ -f "$CA_CERT" ]]; then
  echo "ERROR: CA already exists in ${CA_DIR}." >&2
  echo "       Delete ca.key and ca.crt to regenerate (this invalidates all"
  echo "       existing service certificates)." >&2
  exit 1
fi

mkdir -p "$CA_DIR"

echo "Generating root CA in ${CA_DIR}..."

# ------------------------------------------------------------------------------
# CA private key
# ------------------------------------------------------------------------------
# 4096-bit RSA. Larger than service keys (2048-bit) because the CA is the
# root of trust — its compromise invalidates the entire platform's mTLS.

openssl genrsa -out "$CA_KEY" 4096 2>/dev/null

# ------------------------------------------------------------------------------
# CA self-signed certificate
# ------------------------------------------------------------------------------
# Valid for 10 years (3650 days). Longer than service certificates (1 year)
# because rotating the CA requires reissuing every service certificate.
#
# The `basicConstraints=critical,CA:TRUE` extension marks this as a CA
# certificate. Services will refuse to accept it as a leaf certificate.

MSYS2_ARG_CONV_EXCL="${MSYS2_ARG_CONV_EXCL:+${MSYS2_ARG_CONV_EXCL};}/C=" openssl req -x509 \
  -new \
  -nodes \
  -key "$CA_KEY" \
  -sha256 \
  -days 3650 \
  -out "$CA_CERT" \
  -subj "/C=US/ST=CA/L=San Francisco/O=Order Management Platform/OU=Internal CA/CN=OMP Internal Root CA" \
  -addext "basicConstraints=critical,CA:TRUE" \
  -addext "keyUsage=critical,keyCertSign,cRLSign" \
  -addext "subjectKeyIdentifier=hash"

# ------------------------------------------------------------------------------
# Permissions
# ------------------------------------------------------------------------------
# The private key is 0600 (readable only by the owner). The certificate is
# 0644 (world-readable).

chmod 600 "$CA_KEY"
chmod 644 "$CA_CERT"

# ------------------------------------------------------------------------------
# Print summary
# ------------------------------------------------------------------------------

echo ""
echo "CA generated successfully."
echo ""
echo "  CA private key: $CA_KEY"
echo "  CA certificate: $CA_CERT"
echo ""
echo "IMPORTANT: Save ${CA_KEY} in a password manager or offline backup."
echo "           Losing it means you cannot issue or rotate service certificates."
echo ""
echo "Next: generate certificates for each service:"
echo "  ./generate-service-cert.sh order-service --output ${OUTPUT_DIR}"
echo "  ./generate-service-cert.sh inventory-service --output ${OUTPUT_DIR}"
echo "  ./generate-service-cert.sh payment-service --output ${OUTPUT_DIR}"
echo ""
