#!/usr/bin/env bash
# Test-only issuer and deliberately invalid client credentials for isolated acceptance.
set -euo pipefail
if [[ $# -ne 2 || "$1" != --output ]]; then
  echo 'Usage: bash scripts/setup-grpc-acceptance.sh --output <disposable issuer directory>' >&2
  exit 1
fi
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
bash "$ROOT/scripts/ci-setup.sh" --output "$2"
ISSUER="$(cd "$2" && pwd)"
for fixture in untrusted expired wrong-usage; do
  directory="$ISSUER/$fixture"
  mkdir -p "$directory"
  if [[ -e "$directory/tls.key" || -e "$directory/tls.crt" ]]; then
    [[ -f "$directory/tls.key" && -f "$directory/tls.crt" ]] || { echo 'Incomplete acceptance fixture' >&2; exit 1; }
    continue
  fi
  if [[ "$fixture" == untrusted ]]; then
    openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj '/CN=untrusted.invalid' \
      -addext 'basicConstraints=critical,CA:FALSE' -addext 'extendedKeyUsage=serverAuth,clientAuth' \
      -keyout "$directory/tls.key" -out "$directory/tls.crt" 2>/dev/null
  else
    openssl req -new -newkey rsa:2048 -nodes -subj '/CN=acceptance-client' \
      -keyout "$directory/tls.key" -out "$directory/tls.csr" 2>/dev/null
    usage=clientAuth
    days=1
    if [[ "$fixture" == wrong-usage ]]; then usage=serverAuth; else days=0; fi
    printf 'basicConstraints=critical,CA:FALSE\nkeyUsage=digitalSignature,keyEncipherment\nextendedKeyUsage=%s\n' "$usage" > "$directory/extensions"
    openssl x509 -req -in "$directory/tls.csr" -CA "$ISSUER/ca/ca.crt" -CAkey "$ISSUER/ca/ca.key" \
      -set_serial "0x$(openssl rand -hex 16)" -days "$days" -sha256 -extfile "$directory/extensions" \
      -out "$directory/tls.crt" 2>/dev/null
    rm -- "$directory/tls.csr" "$directory/extensions"
  fi
  chmod 600 "$directory/tls.key"
done
printf 'Isolated gRPC issuer and negative client fixtures are ready.\n'
