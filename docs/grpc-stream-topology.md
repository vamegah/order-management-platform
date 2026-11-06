# Supported gRPC stream topology

Inventory `StreamStockUpdates` and payment `StreamPaymentEvents` register
subscribers inside the service process. Mutations publish to that local registry;
Kafka outbox delivery does not distribute these gRPC notifications. Running two
replicas can therefore silently miss notifications when the mutation reaches the
other replica.

The supported deployment uses **one inventory replica and one payment replica**.
The production overlay explicitly sets one replica, one update/rollback task at a
time, and `stop-first` ordering for both operations. Starting a replacement before
stopping its predecessor creates the same unsafe overlap as scaling to two.
Updates have a service interruption. Do not override replica counts, run a second
independent deployment against the same database, or add a horizontal autoscaler
until cross-process fan-out has been implemented and accepted.

CI resolves the complete production Compose configuration and passes its JSON to
`scripts/check_stream_topology.py`. The check rejects absent constraints, global
mode, scaling and overlapping updates or rollbacks. Run the same check after any
environment-specific overrides, with that environment's interpolation variables:

```sh
docker compose --env-file .env.prod -f docker-compose.yml \
  -f docker-compose.prod.yml config --format json \
  | python scripts/check_stream_topology.py
```

On process replacement, existing streams end and subscriptions disappear.
Consumers must reconnect with a valid JWT and a bounded exponential backoff,
resubscribe, and reconcile current stock/payment state through the read RPCs.
The stream has no durable cursor or replay guarantee. Both handlers drop events
when a subscriber is not ready, so periodic reconciliation is also required for
slow consumers. An open connection alone does not establish that client state is
current. Applications requiring a complete transition history need a durable
consumer contract rather than treating these streams as an event log.

The resolved production configuration and gate regressions are locally checked.
An executed process-replacement/resubscription scenario remains required before
closing the stream task in `TASKS.md`. Production infrastructure, secrets,
certificate rotation and high availability require their separate acceptance;
this constraint does not establish production readiness.
