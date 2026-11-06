# Incident triage and bounded recovery

Reviewed 2026-11-06. These steps use implemented inspection/retry mechanisms.
They do not replace an executed database restore, certificate rotation or
production rollback procedure. Record the operator, environment, time, affected
IDs and evidence for every action; keep tokens, private keys and signing secrets
out of logs and incident attachments.

1. Identify the actual deployment/project and its service versions. Preserve
   `compose ps -a`, bounded service logs, health responses, source/image/JAR hashes
   and correlation IDs. Separate ingress readiness from application readiness.
   Do not restart unrelated projects or discard volumes while collecting evidence.
2. Check dependencies before retrying a business mutation: database availability,
   Kafka topic/partition assignments, Redis, issuer/JWKS and downstream transport.
   A missing Docker CLI or sandbox socket error does not establish engine failure.
   A PKIX error can indicate a substituted certificate; validate issuer/SAN/client
   identity rather than expanding trust to make the error disappear.
3. Read canonical state and persisted references. Use the order ID's recorded
   payment/reservation IDs, status history and queued work. Do not create replacement
   resources to discover an uncertain existing result. A cancelled order can still
   have remote cleanup work; a pending refund is not permission for a second charge
   or refund with a new idempotency key.
4. Select the implemented recovery mechanism for that failure:

   | Symptom | Procedure | Check before action |
   | --- | --- | --- |
   | Checkout response loss or cancelled resources still active | [Order cleanup/reconciliation](../order-cleanup.md) | Correct owner/order references, task state and service scope |
   | Shipment remains FULFILLING | [Durable fulfillment](../order-fulfillment.md) | Committed payment, reservation identity and pending task state |
   | Refund outcome uncertain or callback missing | [Refund operations](../payment-refunds.md) | Existing intent, lease/backoff and provider reference |
   | Unknown refund reference after replay window | [Audited reconciliation](../refund-reconciliation.md) | Administrator identity and verified original provider metadata |
   | Reservations expire late | [Expiry operations](../reservation-expiry.md) | Clock, lease/retry state, worker configuration and metrics |
   | Poisoned outbound delivery | [Notification/DLQ scope](../notification-verification.md) | Persisted delivery, subscriber identity and replay authorization |
   | Silent/reconnected browser | [Client recovery](../websocket-client-guide.md) | Valid subject/expiry, ownership, active subscription and canonical state |

   Use bounded existing retry actions. Respect Retry-After, provider replay windows
   and lease ownership; concurrent manual retries can create uncertainty even when
   the original handler is idempotent. Background workers are opt-in and need the
   documented service identity. The user JWT from the original request is not a
   durable worker credential.
5. Verify completion at the business boundary and its durable side effects:
   status/history, remote resource ownership/state, outbox/drain progress, delivery
   history and recovered client state. A 200 health response, successful message
   publish or empty error log alone does not prove recovery.
6. Retain the before/after evidence and unresolved work. Escalate database restore,
   partition/DLQ reconstruction, expired unknown-reference provider outcomes,
   certificate/secret rotation and deployment rollback to a reviewed procedure.
   Their dry-run acceptance remains open; do not invent destructive repair SQL or
   reset broker offsets based only on an application symptom.

Inventory/payment streams are limited to one replica. Replacement loses their
subscription registries, and slow readers can miss updates. Reconnect and read
canonical state; do not temporarily scale to two while investigating. Notification
Redis fan-out likewise provides live hints rather than a durable replay history.
