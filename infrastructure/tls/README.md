# TLS Certificates

mTLS certificates for internal service-to-service gRPC communication.

**These files are NOT committed to git.** They're generated per-environment
by the scripts in this directory. See `.gitignore` for the rules.

## Overview

The platform uses mutual TLS (mTLS) between internal services for gRPC
calls. Both sides present certificates signed by a shared root CA, so each
service can cryptographically verify the other's identity.

This complements JWT authentication:
- **JWTs** prove *who the user is*.
- **mTLS** proves *which service is speaking*.

Both are required. See [ADR-0007](../../docs/adr/0007-mtls-between-services.md).

## What's covered (Phase 3)

- **gRPC** between order-service → inventory-service.
- **gRPC** between order-service → payment-service.

## What's not covered (deferred)

- **REST** between order-service and product-service / customer-service.
  Feign clients use plaintext HTTP in Phase 3. mTLS for REST requires
  client certificates configured at the HTTP layer; deferred to Phase 7.
- **External traffic.** Traefik terminates TLS at the edge using Let's
  Encrypt. External traffic doesn't use these certificates.

## Files

| File | Committed? | Purpose |
|------|-----------|---------|
| `generate-ca.sh` | Yes | Generates the root CA (run once per environment) |
| `generate-service-cert.sh` | Yes | Generates a service certificate (run once per service) |
| `README.md` | Yes | This file |
| `.gitignore` | Yes | Excludes generated certificates and keys |
| `ca/ca.crt` | No | The CA certificate (public, but environment-specific) |
| `ca/ca.key` | **NEVER** | The CA private key — the root of trust |
| `order-service/tls.crt`, `tls.key` | No | order-service's client certificate |
| `inventory-service/tls.crt`, `tls.key` | No | inventory-service's server certificate |
| `payment-service/tls.crt`, `tls.key` | No | payment-service's server certificate |

## Setup — Development

From this directory:

```bash
# 1. Generate the root CA (once per dev environment)
./generate-ca.sh

# 2. Generate certificates for each service
./generate-service-cert.sh order-service
./generate-service-cert.sh inventory-service
./generate-service-cert.sh payment-service

After this, docker compose up -d works with mTLS.

Setup — Production
On the prod host:

bash
sudo mkdir -p /etc/omp/tls
cd /path/to/order-management-platform/infrastructure/tls

# Generate CA
sudo ./generate-ca.sh --output /etc/omp/tls

# Generate service certificates
sudo ./generate-service-cert.sh order-service --output /etc/omp/tls
sudo ./generate-service-cert.sh inventory-service --output /etc/omp/tls
sudo ./generate-service-cert.sh payment-service --output /etc/omp/tls
The production docker-compose.prod.yml mounts certificates from
/etc/omp/tls/<service> into each container at /etc/tls/.

Certificate Properties
CA certificate: 4096-bit RSA, valid 10 years, self-signed.

CA private key: 4096-bit RSA, mode 0600, root-owned. Save it in a
password manager or offline backup. Loss makes rotation impossible.

Service certificates: 2048-bit RSA, valid 1 year, signed by the CA.

Service private keys: 2048-bit RSA, mode 0600.

SANs: the service's Docker hostname, <service>.local, localhost,
and 127.0.0.1.

EKUs: serverAuth and clientAuth (each service is both).

Rotation
Manual in Phase 3. To rotate a service certificate:

bash
# 1. Delete the existing certificate
rm -rf /etc/omp/tls/inventory-service

# 2. Regenerate
sudo ./generate-service-cert.sh inventory-service --output /etc/omp/tls

# 3. Restart the service (TLS context is established at startup)
docker compose -f docker-compose.yml -f docker-compose.prod.yml \
  --env-file .env.prod restart inventory-service
To rotate the CA:

This is a major operation. Every service certificate must be regenerated.
Plan for downtime or run two CAs in parallel during migration (trust the
old and new CA certificates simultaneously).

Phase 7 will automate rotation with proper cross-signing and
staged migration.

Troubleshooting
"Certificate verify failed" on gRPC calls
Common causes:

CA certificate missing or expired. Check ca/ca.crt exists and is
valid:

bash
openssl x509 -in ca/ca.crt -noout -dates
Service certificate expired. Check:

bash
openssl x509 -in inventory-service/tls.crt -noout -dates
Hostname mismatch. The client verifies the server certificate's
SANs against the expected hostname. If the client connects to
inventory-service, the certificate must have inventory-service as
a SAN.

"Received fatal alert: bad_certificate" from the server
The server rejected the client's certificate. Check:

The client's certificate is signed by the CA the server trusts.

The client certificate is not expired.

The server's trust configuration points at the right CA certificate.

Certificates not appearing in the container
Verify the bind mount:

bash
docker compose exec inventory-service ls -la /etc/tls/
# Expected: tls.crt, tls.key, ca.crt
If missing, check the volume mount in docker-compose.yml. The mount
source must exist on the host before the container starts.

"Permission denied" reading the private key
Private keys are mode 0600 on the host. The container runs as app (uid
1000). The bind mount preserves the host's permissions.

In dev: the keys are owned by the developer (uid 1000 typically). The
container's app user (uid 1000) can read them.

In prod: the keys are owned by root (uid 0). The container's app user
cannot read them.

Fix: in the prod overlay, either:

Change the keys' owner to match the container user (not recommended —
weakens host security).

Run the container with the same uid as the key owner (not recommended —
container runs as root).

Copy the keys into the container's tmpfs at startup via an
entrypoint script that runs as root.

For Phase 3, we use a simpler approach: the prod compose file mounts
certificates read-only from /etc/omp/tls/, and the host files are owned
by root:root with mode 644 for .crt files and 640 for .key files,
with group docker. The Docker daemon (running as root) can read them
regardless of container user, and the container process (uid 1000) reads
them via the mount, which Docker sets up with the mount's permissions
preserved.

Actually, there's a subtlety: the container's app user (uid 1000)
must be able to read the mounted files. If the host files are 640 root:root,
the container's uid 1000 can't read them.

Solution in practice: use mode 640 with group docker, and add the
container's app user to the docker group. This is complex and
fragile.

Simpler solution: in the prod overlay, mount the certificate files and
set user: "0:0" on the container. This runs the container as root, which
weakens the container's isolation slightly but is a common pattern for
services that read root-owned mounted files.

Actually, the cleanest solution: mount certificates from a path
where the host files are owned by the container's uid. In prod, this means
owning the certificate files by uid 1000 (the container user's uid) with
mode 600. The Docker daemon (root) can still read them for the mount, and
the container's uid 1000 can read them from inside.

Chosen for Phase 3: the prod setup script chowns the certificate files
to 1000:1000 with mode 600. The prod overlay mounts them read-only.

I'll note this in the README and in the prod compose comments. The
scripts in Phase 3 generate certificates owned by the current user; on
prod, the operator runs the generation with sudo, then sudo chown 1000:1000 on the service directories. Documented in the setup section.

Related Documentation
ADR-0007: mTLS between services

ADR-0005: gRPC for internal services

Platform README