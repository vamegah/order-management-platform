# Architecture decisions

These accepted decisions describe design choices. Their acceptance does not mean
that deployed behavior, rotation, security or recovery has been verified. Use
[TASKS.md](../../TASKS.md) and [the implementation report](../task-implementation-report.md)
for current implementation and acceptance evidence.

| ADR | Decision |
|---|---|
| [0001](0001-schema-per-service.md) | One PostgreSQL instance with service-owned schemas |
| [0002](0002-promtail-for-logs.md) | Promtail for log shipping |
| [0003](0003-dev-prod-compose-overlay.md) | Development and production Compose overlays |
| [0004](0004-shared-module-strategy.md) | Shared domain, web, protobuf and event libraries |
| [0005](0005-grpc-for-internal-services.md) | gRPC for internal inventory/payment operations |
| [0006](0006-grpc-client-integration.md) | Generated gRPC clients and orchestration |
| [0007](0007-mtls-between-services.md) | Mutual TLS between internal services |
| [0008](0008-compensation-strategy.md) | Saga compensation; extended by durable cleanup/fulfillment |
| [0009](0009-kafka-event-architecture.md) | Kafka event architecture |
| [0010](0010-websocket-subscription-model.md) | Authenticated, owned WebSocket subscriptions |
| [0011](0011-real-time-architecture.md) | Kafka-to-Redis-to-WebSocket fan-out |
| [0012](0012-transactional-outbox.md) | Transactional event outboxes |

Current refinements are documented in [order cleanup](../order-cleanup.md),
[fulfillment](../order-fulfillment.md), [refund reconciliation](../refund-reconciliation.md),
[WebSocket behavior](../../notification-service/README.md) and
[contract evolution](../contract-evolution.md). Automatic rotation, durable
real-time replay and multi-replica acceptance remain open requirements.
