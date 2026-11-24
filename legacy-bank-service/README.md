# Legacy bank service

A Spring-WS SOAP authority with a PostgreSQL ledger, structured BankingFaults,
WS-Security UsernameToken/Timestamp authentication and trusted TLS. Flyway owns
the bank_ledger schema. Transfers atomically record both balance movements and a
durable request/result; reversals cannot repeat or overdraw the destination.

The frozen v1 WSDL remains at `/ws/banking.wsdl`. Adapter transfers carry mandatory
`{urn:omp:banking:request:v1}RequestId`; the bank deduplicates by that identity and
rejects a changed payload. Legacy clients without the header remain supported but
must not retry an ambiguous write as a fresh intent.

Use the root Docker build context and `scripts/setup-banking-development.sh` for
a fresh development Compose deployment. The docker profile seeds demonstration
accounts once; prod never seeds accounts. TLS and credentials come from mounted
files, and nonce hashes survive restarts. Private readiness listens on 9090.

[Deployment, rotations and recovery](../docs/soap-operations.md) ·
[Error contract](../docs/soap-fault-contract.md) ·
[Verification](../docs/banking-verification.md).
