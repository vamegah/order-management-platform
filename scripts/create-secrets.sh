#!/usr/bin/env bash
# ==============================================================================
# create-secrets.sh — Generate production secrets for the Order Management Platform
# ------------------------------------------------------------------------------
# USAGE
#   sudo ./scripts/create-secrets.sh
#
# PURPOSE
#   Creates every Docker secret this platform requires on a fresh production
#   host. Writes them to /etc/omp/secrets/ with mode 0600, owner root. The
#   path matches what docker-compose.prod.yml expects.
#
# WHAT IT GENERATES
#   Every "internal" password the platform needs. Values are 32 random bytes
#   from openssl, base64-encoded (~44 printable characters).
#
# WHAT IT DOES NOT GENERATE
#   - External API keys (Stripe, etc.). Those come from the provider; the
#     operator stores them manually.
#   - TLS certificates. Traefik obtains them from Let's Encrypt at runtime.
#
# SAFETY
#   - Refuses to overwrite existing secret files. Rotation is a separate
#     operation with its own runbook.
#   - Fails fast if not run as root.
#   - Logs every action. Pipe to tee if you want a permanent record.
#
# EXIT CODES
#   0 — all secrets created (or all already existed)
#   1 — permission error (not root)
#   2 — expected tool not found
#   3 — user aborted (e.g., at the pgBackRest confirmation prompt)
#
# ==============================================================================

set -euo pipefail

# ------------------------------------------------------------------------------
# Configuration
# ------------------------------------------------------------------------------

SECRETS_DIR="/etc/omp/secrets"

# All auto-generated secrets. Each becomes a file:
#   /etc/omp/secrets/<name>
# containing a base64-encoded random value.
AUTO_SECRETS=(
  # Platform infrastructure
  "postgres_superuser_password"
  "postgres_app_password"
  "redis_password"
  "redis_sentinel_password"
  "keycloak_admin_password"
  "grafana_admin_password"
  "kafka_broker_password"

  # Per-service database passwords (Phase 1)
  "products_svc_password"
  "customers_svc_password"
  "orders_svc_password"

  # Per-service database passwords (Phase 3)
  "inventory_svc_password"
  "payments_svc_password"

  # To be added in future phases:
  #   Phase 5: payment provider webhook signing secret (but that's provider-supplied)
  #   Phase 6: legacy banking system credentials (provider-supplied)
)

# The pgBackRest repository cipher is special: the operator MUST save it
# before the script exits, or all backups become unreadable. The script
# generates it, prints it once, and waits for confirmation.
BACKUP_CIPHER_NAME="pgbackrest_repo_cipher"

# ------------------------------------------------------------------------------
# Helpers
# ------------------------------------------------------------------------------

log() {
  printf '[%s] %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"
}

error() {
  printf '[%s] ERROR: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >&2
}

# Detect OS for cross-platform notes. Not strictly required, but affects
# how we advise the user to edit files.
detect_os() {
  case "$(uname -s)" in
    Darwin) echo "macOS" ;;
    Linux)  echo "Linux" ;;
    *)      echo "unknown" ;;
  esac
}

# Generate a strong random value. 32 bytes base64.
generate_value() {
  openssl rand -base64 32
}

# Write a value to a secret file with mode 0600 owner root:root.
# Refuses to overwrite an existing file.
write_secret() {
  local name="$1"
  local value="$2"
  local path="${SECRETS_DIR}/${name}"

  if [ -e "$path" ]; then
    log "SKIP  ${name} (already exists)"
    return 1   # signal: skipped
  fi

  # Create an empty file with the right permissions BEFORE writing the value.
  # This avoids a window where the file has default (0644) permissions.
  install -m 600 -o root -g root /dev/null "$path"

  # Write without a trailing newline. Some readers (Docker secrets, Java
  # PropertySource) include the trailing newline in the value, which breaks
  # authentication in subtle ways.
  printf '%s' "$value" > "$path"

  log "OK    ${name}"
  return 0
}

# ------------------------------------------------------------------------------
# Pre-flight checks
# ------------------------------------------------------------------------------

if [ "$(id -u)" -ne 0 ]; then
  error "This script must run as root (to write to ${SECRETS_DIR} with mode 0600)."
  error "Try: sudo $0"
  exit 1
fi

for tool in openssl install; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    error "Required tool not found: $tool"
    error "On Debian/Ubuntu: apt-get install -y openssl coreutils"
    error "On RHEL/CentOS:   yum install -y openssl coreutils"
    error "On Alpine:        apk add openssl coreutils"
    exit 2
  fi
done

# ------------------------------------------------------------------------------
# Banner
# ------------------------------------------------------------------------------

cat <<'BANNER'
================================================================================
  Order Management Platform — Production Secret Generator
================================================================================

This script will:

  1. Create ${SECRETS_DIR} if it doesn't exist (mode 0700, owner root).
  2. Generate 12 random internal secrets (32 bytes each, base64-encoded).
  3. Generate 1 backup encryption cipher (printed once; save it externally).
  4. Refuse to overwrite any existing secret file.

It will NOT:
  - Rotate existing secrets (use the rotation runbook for that).
  - Write secrets to your shell history or any log file.
  - Handle external API keys (Stripe, etc.); those come from providers.

BANNER

printf '\nPress Enter to continue, Ctrl+C to abort: '
read -r _ || { echo; log "Aborted by user"; exit 3; }

# ------------------------------------------------------------------------------
# Create the secrets directory
# ------------------------------------------------------------------------------

if [ -d "$SECRETS_DIR" ]; then
  log "${SECRETS_DIR} already exists."
else
  log "Creating ${SECRETS_DIR} (mode 0700, owner root)."
  install -d -m 700 -o root -g root "$SECRETS_DIR"
fi

# ------------------------------------------------------------------------------
# Generate the auto-generated secrets
# ------------------------------------------------------------------------------

log ""
log "Generating internal secrets..."
log ""

created=0
skipped=0

for name in "${AUTO_SECRETS[@]}"; do
  value="$(generate_value)"
  if write_secret "$name" "$value"; then
    created=$((created + 1))
  else
    skipped=$((skipped + 1))
  fi
  unset value
done

# ------------------------------------------------------------------------------
# pgBackRest repository cipher — special handling
# ------------------------------------------------------------------------------

CIPHER_PATH="${SECRETS_DIR}/${BACKUP_CIPHER_NAME}"

if [ -e "$CIPHER_PATH" ]; then
  log ""
  log "SKIP  ${BACKUP_CIPHER_NAME} (already exists)"
  log "      The existing cipher is preserved. If you've lost it, backups"
  log "      created with it are unreadable. See docs/runbooks/restore-postgres.md."
else
  CIPHER_VALUE="$(generate_value)"

  # Save to file first, then print. If the operator misses the print, the
  # value is still on disk — the warning below tells them to capture it.
  if write_secret "$BACKUP_CIPHER_NAME" "$CIPHER_VALUE"; then
    created=$((created + 1))
  fi

  # Print with a prominent warning. The value is printed once and not
  # stored anywhere else in the output.
  cat <<EOF

================================================================================
  ⚠  IMPORTANT — BACKUP ENCRYPTION CIPHER
================================================================================

  pgBackRest will encrypt every backup with the following cipher. If you
  lose this value, your backups become unreadable. If it leaks, your
  backups are compromised.

  Store it in your password manager NOW, before continuing.

  Cipher value:

      ${CIPHER_VALUE}

  After you save this value, you can hide it from your scrollback:
      clear

  The same value is stored at:
      ${CIPHER_PATH}

================================================================================

EOF

  unset CIPHER_VALUE
fi

# ------------------------------------------------------------------------------
# Summary
# ------------------------------------------------------------------------------

cat <<EOF

================================================================================
  Summary
================================================================================

  Secrets directory: ${SECRETS_DIR}
  Created this run:  ${created}
  Skipped (existed): ${skipped}

  Files (all should show mode -rw------- root root):

EOF

# Use ls with explicit formatting; the shell expands SECRETS_DIR safely.
ls -l "${SECRETS_DIR}"

cat <<EOF

================================================================================
  Next steps
================================================================================

  1. If you generated a new pgBackRest cipher above, save it in your password
     manager NOW.

  2. Verify all files have mode 0600:

       sudo ls -l ${SECRETS_DIR}

     Every file should be -rw------- root root. If any differ, fix them:

       sudo chmod 600 ${SECRETS_DIR}/*
       sudo chown root:root ${SECRETS_DIR}/*

  3. Populate .env.prod (see .env.prod.example for the template).

  4. Deploy:

       docker compose -f docker-compose.yml -f docker-compose.prod.yml \\
         --env-file .env.prod up -d

  5. Verify services started healthy:

       docker compose ps

================================================================================

EOF

log "Done."