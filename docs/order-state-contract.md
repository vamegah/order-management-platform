# Order state and concurrency contract

| Current state | Allowed next states |
| --- | --- |
| DRAFT | PLACED, CANCELLED |
| PLACED | CONFIRMED, CANCELLED |
| CONFIRMED | FULFILLING, CANCELLED |
| FULFILLING | SHIPPED, CANCELLING |
| CANCELLING | CANCELLED, SHIPPED |
| SHIPPED | DELIVERED |
| DELIVERED | None |
| CANCELLED | None |

The aggregate appends history only for a valid transition. Each forward transition (placement, confirmation, shipment intent, shipment completion and delivery) requires 1-100 lines with distinct, verified non-NULL reservation IDs. Placement records its time only after these checks pass. Invalid transitions preserve state and history and are exposed as HTTP 409. Cancellation remains available from its existing allowed states even when historical reference metadata is missing. Service-level repeated cancellation is idempotent and can retry pending cleanup without adding another cancellation event.

Orders now use a database version column. Concurrent modifications cannot both commit their aggregate/history/outbox changes; a losing optimistic update rolls back and returns a redacted HTTP 409 problem instructing the caller to reload and retry. The migration initializes existing orders to version zero. The order UUID is assigned by checkout before remote calls and is preserved during JPA persistence. Full-detail reads fetch lines and initialize history separately to avoid fetching two list bags together.

`OrderStateTransitionsTest` covers a complete journey, cancellation before shipping, and invalid/terminal transitions preserving history. The MVC conflict contract test exercises the public problem response. `OrderPersistenceIT` exercises assigned IDs, reservation references, both collections, competing transitions, and outbox rollback using PostgreSQL/JPA. Its eight PostgreSQL cases now pass after the order mapping/startup repairs. Run `./mvnw -pl order-service -am verify -Dit.test=OrderPersistenceIT -Dfailsafe.failIfNoSpecifiedTests=false`; see [verification scope and results](order-verification.md).

Public reads by ID/number, placement and cancellation require an administrator or the verified customer owner. Creation requires an administrator or a matching `customer_id` claim. Claimless/foreign access is denied; the JWT subject is not used as a customer ID. See [the access policy](order-verification.md#public-authorization).

Confirmation and shipment remain administrator actions. Before either transition, order-service reads the linked payment through GetPayment with a five-second per-attempt deadline and the caller JWT. The payment must be fully CONFIRMED, have a provider reference, and match the linked payment ID, order ID, customer ID, total amount, and currency. Pending, failed, cancelled, partially refunded, and fully refunded payments cannot advance an order. Missing links and invalid starting states fail before any RPC. A missing referenced payment is an order conflict; transport/authentication failures or malformed payment responses produce a redacted HTTP 502 problem.

The payment read happens outside the order mutation transaction. The local transaction reloads the order, verifies the snapshot version and payment link, and repeats the payment/order identity and amount comparison before changing state. A concurrent local update or cancellation cannot be overwritten. History and the outbox event remain in the same transaction as the transition. Order forbidden/gateway problems now carry body status 403/502 consistently with their HTTP status and omit downstream exception details.

`PaidOrderTransitionContractTest` exercises HTTP -> real order orchestration -> in-process payment gRPC with a mocked local transaction layer. It covers successful confirmation/shipping, rejected payment states and mismatches, stale order snapshots/cancellation, caller-token forwarding, the RPC deadline, and redacted failure responses. These tests do not replace database concurrency or deployed authorization acceptance.

Shipment persists FULFILLING before inventory mutation and records a durable payment decision before completing SHIPPED. Cancellation after fulfillment begins returns CANCELLING/202 until the shared payment authority chooses shipment or compensation. Stock compensation completes before remaining refunds are admitted. The scheduler coordinates accepted-order expiry and retains attention/retry evidence on unresolved failures. See the [complete payment/inventory/order transition matrix and deployment procedure](core-order-lifecycle.md).

## Reference integrity on forward transitions

These local guards reject missing or shared pointers before state/history changes.
Checkout and audited repair separately verify remote resource ownership; a non-NULL
UUID alone is not proof of an existing hold, its warehouse, current state or
ownership. No transition allocates replacement holds or rewrites legacy pointers.
Existing unresolved historical rows remain visible for investigation.

`OrderReservationIntegrityTest` exercises twenty invalid state/shape combinations,
valid one/100-line journeys and cancellation of unresolved historical orders.
`OrderReferenceIntegrityIT` executes real HTTP/PostgreSQL calls over fixture
inventory/payment RPCs. It retries missing/shared-pointer commands, compares full
aggregate/line/history/outbox/journal/queue/audit rows and downstream resources,
then proves a complete verified repair permits placement and confirmation.
Historical cancellation never fabricates references. The approved upgrade policy
records explicit terminal exceptions for irrecoverable historical lines, with
original identities and audited cancellation/cleanup. Deferred final constraints
and unique verified claims protect future and resolved orders. See the
[current lifecycle acceptance and historical policy](core-order-lifecycle.md).
