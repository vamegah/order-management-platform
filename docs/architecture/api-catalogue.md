# API catalogue

Reviewed 2026-11-06. This maps implemented entry points to their behavior documents;
versioned OpenAPI 3.1 contracts and comprehensive consumer-drift enforcement remain
open. Runtime Swagger URLs do not replace published compatibility promises.
Development hostnames below are Traefik routes unless marked internal.

| Boundary | Address / route | Operations and caller policy | Contract |
| --- | --- | --- | --- |
| Product/catalog REST | `product-service.localhost/api/v1/products`, `/api/v1/categories` | Authenticated reads/search/SKU/batch; administrator product writes and image operations | [Batch semantics](../batch-read-contract.md), [catalog verification](../catalog-customer-verification.md) |
| Customer REST | `customer-service.localhost/api/v1/customers` | `/me`, owner/admin profile/address reads; administrator list/email lookup, writes and identity linking; batch is admin or one verified self ID | [Ownership and persistence](../catalog-customer-verification.md) |
| Order REST | `order-service.localhost/api/v1/orders` | Owner/admin create/read/place/cancel; payment-backed lifecycle and administrator fulfillment actions | [State](../order-state-contract.md), [fulfillment](../order-fulfillment.md) |
| GraphQL | `graphql.localhost/graphql` | JWT relay, bounded aggregation/batching, depth/complexity/body/rate/time policies | [Production policy](../graphql-production-policy.md), checked-in GraphQL schema |
| Inventory gRPC | Internal `inventory-service:9091` | Stock/reservation queries, reserve/release/commit, client streaming, stock streams and recovery lookup; mTLS and bearer identity | [Reservation semantics](../inventory-reservations.md), [proto evolution](../contract-evolution.md) |
| Payment gRPC | Internal `payment-service:9093` | Intent create/confirm/cancel/read, refunds, event stream and scoped recovery lookup; mTLS and bearer identity | [Idempotency](../payment-idempotency-contract.md), [refunds](../payment-refunds.md) |
| Payment REST/webhooks | Payment HTTP listener on the app network | Authenticated payment/refund reads/actions with owner/admin policy; provider-specific signed raw webhook requests | [Verification](../payment-verification.md), [operator reconciliation](../refund-reconciliation.md) |
| Notification STOMP | `notification.localhost/ws` (SockJS); `/ws/websocket` (native) | JWT CONNECT; order/payment ownership checks, administrator inventory topics and `/user/queue/notifications` | [Client](../websocket-client-guide.md), [verification](../notification-verification.md) |
| Outbound webhook management | Notification `/api/v1/webhook-subscriptions`, `/api/v1/webhook-deliveries` | Owner/admin registration/list/update/delete, secret rotation, test and delivery management | [Implemented scope](../notification-verification.md) |
| Webhook DLQ management | Notification `/api/v1/webhook-dlq` | Administrator list/replay/delete with explicit conflict/not-found outcomes | [DLQ scope](../notification-verification.md) |
| Banking REST | `banking.localhost/api/v1/banking` | POST transfers and GET account balance; facade access policy still needs production acceptance | [REST/SOAP faults](../soap-fault-contract.md), [banking checks](../banking-verification.md) |
| Legacy SOAP | Bank `/ws`, published `/ws/banking.wsdl` | UsernameToken/Timestamp validation and structured business faults | [Frozen WSDL/XSD](../../contracts/soap/v1), [evolution](../contract-evolution.md) |
| Payment-provider simulator | `simulator.localhost` | Development provider HTTP and signed callback behavior; deployed provider journey acceptance remains open | Simulator service README and [payment operations](../payment-refunds.md) |

The application boundary and management port can differ; use each service's
configuration for scrape/health addresses. The e2e overlay publishes inventory
9091, payment 9093 and notification management 18085 on IPv4 loopback for acceptance
clients. It retains mTLS and authenticated metrics. Other app-network management
interfaces should not be exposed by adding public ingress routes for convenience.

Additive order responses include persisted `paymentId` and line `reservationId`;
historical rows may omit these fields. Batch reads are bounded, ordered and
identity-aware. Read the referenced contracts before assuming generic CRUD or
uniform pagination/error behavior across all services. The banking facade,
provider callbacks, administrative operations and browser clients have distinct
trust requirements.

Published protobuf descriptors and SOAP fixtures have exact PR-base compatibility
checks. REST/OpenAPI and complete GraphQL backward-compatibility coverage remain
separate tasks; this catalogue is an index, not an executable schema or a claim
that every endpoint/security/failure case has been accepted.
