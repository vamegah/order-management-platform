# Order state and concurrency contract

| Current state | Allowed next states |
| --- | --- |
| DRAFT | PLACED, CANCELLED |
| PLACED | CONFIRMED, CANCELLED |
| CONFIRMED | FULFILLING, CANCELLED |
| FULFILLING | SHIPPED |
| SHIPPED | DELIVERED |
| DELIVERED | None |
| CANCELLED | None |

The aggregate appends history only for a valid transition. Placement requires a nonempty order and records its placement time. Invalid transitions preserve state and history and are exposed as HTTP 409. Service-level repeated cancellation is idempotent and can retry pending cleanup without adding another cancellation event.

Orders now use a database version column. Concurrent modifications cannot both commit their aggregate/history/outbox changes; a losing optimistic update rolls back and returns a redacted HTTP 409 problem instructing the caller to reload and retry. The migration initializes existing orders to version zero. The order UUID is assigned by checkout before remote calls and is preserved during JPA persistence. Full-detail reads fetch lines and initialize history separately to avoid fetching two list bags together.

`OrderStateTransitionsTest` covers a complete journey, cancellation before shipping, and invalid/terminal transitions preserving history. The MVC conflict contract test exercises the public problem response. `OrderPersistenceIT` exercises assigned IDs, reservation references, both collections, competing transitions, and outbox rollback using PostgreSQL/JPA. Its eight PostgreSQL cases now pass after the order mapping/startup repairs. Run `./mvnw -pl order-service -am verify -Dit.test=OrderPersistenceIT -Dfailsafe.failIfNoSpecifiedTests=false`; see [verification scope and results](order-verification.md).

Public reads by ID/number, placement and cancellation require an administrator or the verified customer owner. Creation requires an administrator or a matching `customer_id` claim. Claimless/foreign access is denied; the JWT subject is not used as a customer ID. See [the access policy](order-verification.md#public-authorization).

Confirmation and shipment remain administrator actions. Before either transition, order-service reads the linked payment through GetPayment with a five-second per-attempt deadline and the caller JWT. The payment must be fully CONFIRMED, have a provider reference, and match the linked payment ID, order ID, customer ID, total amount, and currency. Pending, failed, cancelled, partially refunded, and fully refunded payments cannot advance an order. Missing links and invalid starting states fail before any RPC. A missing referenced payment is an order conflict; transport/authentication failures or malformed payment responses produce a redacted HTTP 502 problem.

The payment read happens outside the order mutation transaction. The local transaction reloads the order, verifies the snapshot version and payment link, and repeats the payment/order identity and amount comparison before changing state. A concurrent local update or cancellation cannot be overwritten. History and the outbox event remain in the same transaction as the transition. Order forbidden/gateway problems now carry body status 403/502 consistently with their HTTP status and omit downstream exception details.

`PaidOrderTransitionContractTest` exercises HTTP -> real order orchestration -> in-process payment gRPC with a mocked local transaction layer. It covers successful confirmation/shipping, rejected payment states and mismatches, stale order snapshots/cancellation, caller-token forwarding, the RPC deadline, and redacted failure responses. These tests do not replace database concurrency or deployed authorization acceptance.

Shipment now commits a durable FULFILLING intent before consuming inventory. Cancellation is fenced from that point. Only acknowledged, matching committed holds and a fresh matching confirmed payment can complete shipment. Pending work returns HTTP 202 and can resume through an administrator retry or an opt-in worker. The final SHIPPED history/outbox event and request completion share one transaction. See [fulfillment behavior and recovery limits](order-fulfillment.md), including the new status/202 client compatibility requirements.

The payment read and local commit are not an atomic cross-service transaction. Payment can still change after the read; refund/shipment coordination remains required. The existing payment-service confirmation API remains the authority for its own state, and provider integration acceptance is separate. Cancelled-order cleanup now requests provider-backed remaining refunds for confirmed payments and waits for a terminal payment state; see [refund coordination](payment-refunds.md). Inventory TTL expiry must remain disabled until the accepted-order lifecycle handles expired holds. The full lifecycle acceptance task remains open.
