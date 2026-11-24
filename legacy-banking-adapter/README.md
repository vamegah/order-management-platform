# Legacy banking adapter

An administrator-only REST facade over the SOAP bank. JWT issuer verification
and the admin realm role protect transfers and balances. The adapter retains
request hashes and terminal results in PostgreSQL bank_adapter, scopes keys by
issuer/subject, and forwards mandatory durable identities to the bank.

Trusted HTTPS, hostname checks, fresh WS-Security nonces/timestamps, bounded socket
deadlines, two classified attempts, an eight-call bulkhead and a circuit breaker
protect the external boundary. Fixed RFC 7807 errors never echo SOAP text or
transport exceptions. Management/Prometheus run on private port 9090 in Compose.

Build with the root Docker context. Development setup creates private mounted
configuration and a public CA-only client truststore; the adapter cannot read the
bank's server key. Production uses external secret/TLS directories.

[Deployment, rotations and recovery](../docs/soap-operations.md) ·
[Error contract](../docs/soap-fault-contract.md) ·
[Declared acceptance limits](../docs/soap-acceptance-objectives.md).
