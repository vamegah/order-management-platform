# Notification client guide

The supported source package is [@omp/notifications](../clients/notifications/README.md).
It replaces the earlier unbuilt reference snippet. Build with `npm ci --ignore-scripts`,
`npm test` and `npm pack` in `clients/notifications`, then install the resulting
archive in the consumer application. CI retains the package; registry publication
is a separate release action.

Connect to the native STOMP endpoint `wss://notification.example/ws/websocket`.
The `/ws` base is for SockJS transports; using it as a native STOMP broker URL is
incorrect. Development localhost connections require an explicit insecure opt-in.
JWTs belong in CONNECT headers, never URLs, source code or browser console output.
Supply fresh credentials, stable subject and expiry through your authentication SDK.
The development realm provides the required `basic` subject and managed customer
claims; existing realms need an explicit migration. See [external setup](external-e2e.md).

Supported destinations are `/topic/orders/{uuid}`, `/topic/payments/{uuid}`,
`/topic/inventory/{sku}` and `/user/queue/notifications`. The server checks ownership
and restricts inventory subscriptions to administrators. A client-side destination
filter does not grant access. Private notifications use the stable issuer subject,
not a display name. Never publish application messages from the client.

The package restores subscriptions, refreshes credentials before expiry, and reads
canonical state after reconnect and live invalidation hints. Periodic reads repair
gaps because the simple broker has no receipts, cursor or durable replay. Hints can
be coalesced under load and must not be applied as a complete transition history.
The package README specifies buffering, retry/time limits, cancellation, identity
changes, and error codes. On logout, disconnect and clear the application's private
state immediately. On a changed caller, old destinations are discarded.

Strict compilation and seventeen real WebSocket/STOMP transport cases cover retry,
refresh, cancellation, missing-event reconciliation, identity changes, bounded
buffers and redacted errors. The separate deployed package test requires the actual
platform. Java external suites additionally verify real JWTs, mTLS, ownership reads,
Kafka/Redis delivery and unsubscribe cleanup. Multi-replica replacement, durable
replay, scheduled server-side expiry closure and load acceptance remain separate
backlog work; none is inferred from reconnect support.
