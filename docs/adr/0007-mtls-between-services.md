# ADR-0007: mTLS for Internal Service Communication

> **Status:** Accepted
> **Date:** 2025-01-15
> **Deciders:** Platform team
> **Supersedes:** —
> **Superseded by:** —

## Context

By the end of Batch 7.4, gRPC traffic between order-service, inventory-service,
and payment-service is protected by JWT authentication: each call carries the
caller's JWT, and downstream services validate it.

This authenticates the **user on whose behalf the call is made**, but it does
not authenticate the **service making the call**. Any process on the `app`
network with a valid JWT can call any gRPC service. In a single-host deployment
with well-behaved containers, this is acceptable. In any environment with
weaker network isolation, it isn't.

The gap is especially visible for internal services: a compromised container
on the same network could impersonate any service.

## Decision

Adopt **mutual TLS (mTLS)** for all internal gRPC communication. Both sides of
every gRPC call present certificates signed by a shared root CA. A gRPC
connection is accepted only if:

1. The client presents a valid certificate signed by our CA.
2. The server presents a valid certificate signed by our CA.
3. The server's certificate matches the hostname the client is connecting to.
4. The user JWT (in `Authorization` metadata) is valid and unexpired.

The CA is local — generated once per environment by the operator — because
public CAs don't issue certificates for internal hostnames.

**Scope (Phase 3):** gRPC only. REST traffic between order-service and
product-service / customer-service stays plaintext.

## Rationale

### Why mTLS in addition to JWT?

JWTs and mTLS address different threats:

- **JWT** answers "which user is this request for?" It's about authorization.
- **mTLS** answers "which service is making this request?" It's about
  service identity.

Both are needed. A system with only JWTs trusts any container on the network
that holds a valid token. A system with only mTLS trusts any authenticated
service but has no notion of users.

**Concrete example:** if a misconfigured service accidentally calls
inventory-service with Alice's JWT (e.g., a bug in request forwarding),
inventory-service would honor the call. With mTLS, the call is only
accepted if the caller's certificate identifies it as order-service.

### Why a local CA?

Public CAs (Let's Encrypt, etc.) issue certificates for public DNS names.
Our services have names like `inventory-service` that don't resolve publicly.
A local CA is the standard solution for internal-only PKI.

**Cost:** the operator must run the CA tooling, keep the CA private key safe,
and rotate service certificates manually. This is Phase 3's scope.

**Alternative considered:** a service mesh (Istio, Linkerd) provides mTLS
out of the box with automatic rotation. Overkill for a single-host
deployment. Phase 7 could adopt a mesh if the platform scales.

### Why only gRPC for now?

REST mTLS requires different tooling:

- For **server-side REST** (product-service, customer-service): configure
  client-certificate authentication in Spring Boot's servlet container.
  This is doable but affects every REST endpoint.
- For **client-side REST** (order-service's Feign clients): configure a
  custom `Client` with an SSLContext that presents a client certificate.
  Feign supports this, but it's non-trivial to configure.

**Phased approach:** deploy mTLS on the newer, simpler gRPC path first.
Once proven, extend to REST in Phase 7.

**Consequence:** the platform's security posture is *partially* mTLS after
Phase 3. Internal gRPC is authenticated on both ends; internal REST is not.
The JWT remains the sole authenticity mechanism for REST calls. This is
acknowledged and accepted for Phase 3.

### Why manual rotation?

Automated rotation requires:
- A service that watches certificates and triggers restarts on expiry.
- Cross-signing during rotation (to avoid downtime).
- A trust bundle that includes both old and new CA certificates during
  the overlap period.

For a single-host deployment with a handful of services, manual rotation
every 12 months is manageable. Phase 7 will automate.

## Consequences

### Positive

- **Service authentication.** Every gRPC call is authenticated on both
  ends. A rogue container on the `app` network cannot impersonate a
  service without its private key.
- **Defense in depth.** Two independent authentication layers: JWT for
  users, mTLS for services.
- **Encryption.** gRPC traffic is encrypted on the network. In the current
  single-host Docker network, this is a smaller win (traffic doesn't leave
  the host), but it protects against:
  - A misconfigured host that bridges networks.
  - Future deployments where services run on multiple hosts.
  - Compliance requirements that mandate encryption in transit.
- **Auditability.** The client certificate identifies the caller in
  server-side traces. A trace shows "order-service called inventory-service,"
  not just "some client called inventory-service."

### Negative

- **Certificate lifecycle management.** The operator must:
  - Generate the CA and service certificates on every environment.
  - Keep the CA private key safe (loss = can't issue new certs).
  - Rotate certificates before expiry (currently manual).
- **Local CA is a single point of failure.** Compromise of the CA key
  invalidates the entire platform's mTLS. The key must be protected
  (mode 0600, offline backup).
- **Configuration complexity.** Each service has mTLS config; each client
  has to know the server's expected hostname (`authority-override`).
- **REST is not covered.** The platform's mTLS posture is partial. REST
  traffic is encrypted only at the Traefik edge, not internally.

### Neutral

- **Certificate size.** 2048-bit RSA adds a few KB per connection handshake.
  Negligible for our traffic profile.
- **Handshake cost.** ~5ms per new connection. gRPC reuses connections, so
  this is a one-time cost per channel.

## Implementation

- **CA tooling:** `infrastructure/tls/generate-ca.sh`.
- **Service certificate tooling:** `infrastructure/tls/generate-service-cert.sh`.
- **Server-side config:** `grpc.server.security.*` in inventory-service and
  payment-service `application.yml`.
- **Client-side config:** `grpc.client.<service>.security.*` in order-service
  `application.yml`.
- **Mounts:** dev mounts from `./infrastructure/tls/`; prod from `/etc/omp/tls/`.
- **Test bypass:** `grpc.server.security.enabled=false` and
  `negotiation-type=PLAINTEXT` in test profile.

## Related Decisions

- **ADR-0005:** gRPC for internal services.
- **ADR-0006:** gRPC client integration.
- **Phase 7 planned:** automate certificate rotation.

## References

- gRPC authentication guide: https://grpc.io/docs/guides/auth/
- gRPC TLS configuration: https://grpc.io/docs/guides/tls/
- OpenSSL cookbook: https://www.feistyduck.com/library/openssl-cookbook/