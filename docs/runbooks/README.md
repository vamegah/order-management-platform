# Runbook catalogue

Reviewed 2026-11-25. Execution scope is recorded separately from implementation.

| Procedure | Scope and evidence |
| --- | --- |
| [Development acceptance](development-acceptance.md) | Dedicated local/CI fixtures and caller boundaries |
| [Incident triage](incident-response.md) | Bounded diagnosis and recovery selection |
| [Release and promotion](release.md) | Signed immutable inputs, mounted credentials, protected approval, compatible rollback; destination-specific promotion still requires provisioning |
| [Operations and recovery](operations.md) | SLO alerts, encrypted backup/PITR, broker/DLQ/outbox replay, certificate/secret rotation; isolated records under `coverage/operations` and required CI artifacts |
| [gRPC production](../grpc-production.md) | mTLS rotation, deadlines and process-local stream topology |
| [SOAP operations](../soap-operations.md) | Banking TLS/WS-Security, durable transfer recovery and accepted deployed rotation |
| [Refund reconciliation](../refund-reconciliation.md) | Administrator recovery preserving provider idempotency |
| [Order cleanup](../order-cleanup.md) | Durable cancellation/refund/hold compensation |

An unexecuted destination-specific procedure remains a draft. Preserve the release/source, hardware, actual measurements, conservation checks and cleanup result for each rehearsal. Deployment rollback preserves data volumes, credential history and independently retained backups.
