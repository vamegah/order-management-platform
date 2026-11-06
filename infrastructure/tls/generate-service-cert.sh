#!/usr/bin/env bash
# ==============================================================================
# generate-service-cert.sh — Generate a service certificate signed by the CA
# ------------------------------------------------------------------------------
# USAGE
#   ./generate-service-cert.sh <service-name> [--output <dir>]
#
# EXAMPLE
#   ./generate-service-cert.sh inventory-service
#   # creates inventory-service/tls.key and inventory-service/tls.crt
#
# WHAT IT GENERATES
#   <service>/tls.key — the service's private key (2048-bit RSA)
#   <service>/tls.crt — the service's certificate, signed by the CA (valid 1 year)
#
# SANs (Subject Alternative Names) included:
#   DNS:<service>            — the Docker Compose service name
#   DNS:<service>.local      — dev convenience
#   DNS:localhost            — for local runs outside Docker
#   IP:127.0.0.1             — for local runs
#
# WHY BOTH serverAuth AND clientAuth EKU:
#   Every service is potentially both a server (inbound) and a client
#   (outbound to other services). Giving both EKUs simplifies certificate
#   management — one cert per service.
#
# SAFETY
#   Refuses to overwrite existing certificates. Delete them to regenerate.
# ==============================================================================

set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <service-name> [--output <dir>]" >&2
  exit 1
fi

SERVICE_NAME="$1"
shift

if [[ ! "$SERVICE_NAME" =~ ^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$ ]]; then
  echo "ERROR: service-name must be a DNS label of at most 63 lowercase letters, digits or hyphens." >&2
  exit 1
fi

OUTPUT_DIR="$(pwd)"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --output)
      OUTPUT_DIR="$2"
      shift 2
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 1
      ;;
  esac
done

CA_DIR="${OUTPUT_DIR}/ca"
CA_KEY="${CA_DIR}/ca.key"
CA_CERT="${CA_DIR}/ca.crt"

SERVICE_DIR="${OUTPUT_DIR}/${SERVICE_NAME}"
SERVICE_KEY="${SERVICE_DIR}/tls.key"
SERVICE_CSR="${SERVICE_DIR}/tls.csr"
SERVICE_CERT="${SERVICE_DIR}/tls.crt"
SERVICE_EXT="${SERVICE_DIR}/tls.ext"

# ------------------------------------------------------------------------------
# Pre-flight checks
# ------------------------------------------------------------------------------

if ! command -v openssl >/dev/null 2>&1; then
  echo "ERROR: openssl is required but not found." >&2
  exit 1
fi

if [[ ! -f "$CA_KEY" ]] || [[ ! -f "$CA_CERT" ]]; then
  echo "ERROR: CA not found at ${CA_DIR}." >&2
  echo "       Run ./generate-ca.sh first." >&2
  exit 1
fi

if [[ -f "$SERVICE_KEY" ]] || [[ -f "$SERVICE_CERT" ]]; then
  echo "ERROR: Certificate for ${SERVICE_NAME} already exists." >&2
  echo "       Delete ${SERVICE_KEY} and ${SERVICE_CERT} to regenerate." >&2
  exit 1
fi

mkdir -p "$SERVICE_DIR"

echo "Generating certificate for ${SERVICE_NAME}..."

# ------------------------------------------------------------------------------
# Service private key
# ------------------------------------------------------------------------------

openssl genrsa -out "$SERVICE_KEY" 2048 2>/dev/null

# ------------------------------------------------------------------------------
# Certificate signing request (CSR)
# ------------------------------------------------------------------------------

MSYS2_ARG_CONV_EXCL="${MSYS2_ARG_CONV_EXCL:+${MSYS2_ARG_CONV_EXCL};}/C=" openssl req -new \
  -key "$SERVICE_KEY" \
  -out "$SERVICE_CSR" \
  -subj "/C=US/ST=CA/L=San Francisco/O=Order Management Platform/OU=Services/CN=${SERVICE_NAME}"

# ------------------------------------------------------------------------------
# Extensions file for the certificate
# ------------------------------------------------------------------------------
# SANs let clients verify the server's identity against a hostname or IP.
# We include:
#   - The service name (Docker DNS resolves this on the internal networks)
#   - A `.local` variant (dev convenience)
#   - localhost and 127.0.0.1 (for local runs and testing)
# ------------------------------------------------------------------------------

cat > "$SERVICE_EXT" <<EOF
basicConstraints = CA:FALSE
keyUsage = critical, digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth, clientAuth
subjectAltName = @alt_names

[alt_names]
DNS.1 = ${SERVICE_NAME}
DNS.2 = ${SERVICE_NAME}.local
DNS.3 = localhost
IP.1  = 127.0.0.1
EOF

# ------------------------------------------------------------------------------
# Sign the CSR with the CA
# ------------------------------------------------------------------------------
# Valid for 365 days (1 year). Rotation is manual in Phase 3.

openssl x509 -req \
  -in "$SERVICE_CSR" \
  -CA "$CA_CERT" \
  -CAkey "$CA_KEY" \
  -CAcreateserial \
  -out "$SERVICE_CERT" \
  -days 365 \
  -sha256 \
  -extfile "$SERVICE_EXT" \
  2>/dev/null

# ------------------------------------------------------------------------------
# Cleanup intermediate files
# ------------------------------------------------------------------------------

rm -f "$SERVICE_CSR" "$SERVICE_EXT"

# ------------------------------------------------------------------------------
# Permissions
# ------------------------------------------------------------------------------

chmod 600 "$SERVICE_KEY"
chmod 644 "$SERVICE_CERT"

# ------------------------------------------------------------------------------
# Verify the certificate
# ------------------------------------------------------------------------------

echo ""
echo "Certificate for ${SERVICE_NAME} generated."
echo ""
echo "  Private key: $SERVICE_KEY"
echo "  Certificate: $SERVICE_CERT"
echo ""
echo "Verifying against CA..."
openssl verify -CAfile "$CA_CERT" "$SERVICE_CERT"
echo ""
echo "Subject and SANs:"
openssl x509 -in "$SERVICE_CERT" -noout -subject -ext subjectAltName
echo ""
