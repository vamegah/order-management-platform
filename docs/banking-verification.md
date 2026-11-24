# Banking boundary verification

Section 6 implements a durable bank ledger and adapter request store, trusted
TLS/WS-Security, fixed REST errors and bounded resilience. The frozen v1 SOAP
contract remains unchanged. See [the operations runbook](soap-operations.md),
[fault contract](soap-fault-contract.md) and
[predeclared deployed objectives](soap-acceptance-objectives.md).

Both modules measure all handwritten production code and enforce at least 80%
instruction coverage, plus the existing per-module PR coverage regression gate.
Only generated JAXB classes are excluded. Unit and real HTTP/SOAP tests cover
ledger atomicity, concurrent duplicates, durable declines, reversal rollback,
credential and nonce rejection, transport classification, breaker transitions,
public error redaction and unsafe client configuration.

Required `soap-e2e` acceptance independently packages both processes and starts
fresh PostgreSQL, Keycloak and Traefik. It exercises all seven deployment phases:
REST/auth/faults/duplicates, WS-Security/replay, lost replies/forced process death,
retry/breaker/deadline behavior, credential rotation, CA rotation, and bounded
load/metrics/redaction. Reports bind results to source and runtime image IDs and
reject skipped/missing phases or unsuccessful cleanup. CI retains no keys or
configtree credentials. Final executed results are recorded when verification
finishes; implementation alone does not close the backlog checkboxes.
