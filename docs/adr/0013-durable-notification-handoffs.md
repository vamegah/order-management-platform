# ADR-0013: Durable notification handoffs and bounded sessions

Status: Implemented; acceptance is tracked in TASKS.md Section 4.

The source outbox, Kafka, Redis, remote HTTP receivers and WebSocket clients cannot
share one transaction. Use stable version-one event identities, one outbox per
source schema, and independent notification inbox identities for live and webhook
handling. Insert all matching dispatch work in the inbox transaction. A bounded
locked drainer publishes due work and marks it only after acknowledgment. Delayed
retries wait in PostgreSQL rather than sleeping on a Kafka partition.

Contract failures and exhausted transient errors enter durable quarantine before
the source offset can commit. Recovery is administrator-only, validated, audited
and fenced by row locks. An unavailable quarantine store preserves the Kafka
record. Retain unresolved work and compact duplicate identities; never erase a
failed record as part of ordinary retention cleanup.

Live Redis/STOMP messages remain invalidation hints. Redis fans out to every
notification replica, and the supported client converges through owned canonical
API reads. Source outbox ordering is per topic/aggregate, with failed predecessors
blocking later publication for that aggregate. Uncertain acknowledgments can
redeliver, so webhook receivers deduplicate the stable delivery identity.

The broker uses caller-scoped order/payment ownership, managed customer claims,
private subject routing, bounded session/subscription limits and cancellable
registration tickets. A late ownership lookup cannot resurrect a closed or
unsubscribed session. Bound the original JWT lifetime to five minutes and close
idle expired sockets on the dedicated WebSocket scheduler; the outbound channel
also rejects expired delivery.

The required event/realtime acceptance uses two replicas, at least 1,000 healthy
clients, explicit failure controls and objectives declared before measurement.
See [operation, contracts and acceptance](../events-and-websockets.md).
