# Banking REST-to-SOAP adapter

A Spring REST facade over the legacy bank's generated JAXB/Spring-WS client.
`POST /api/v1/banking/transfers` and
`GET /api/v1/banking/accounts/{accountNumber}/balance` map to the SOAP contract.
Structured business/authentication/transport faults become redacted HTTP problems;
see [the fault contract](../docs/soap-fault-contract.md).

```sh
./mvnw -pl legacy-banking-adapter,legacy-bank-service -am -Pstatic-analysis clean verify
docker build -f legacy-banking-adapter/Dockerfile -t omp/legacy-banking-adapter:local .
```

[Base configuration](src/main/resources/application.yml) sets HTTP 8085 and the
SOAP URL `https://localhost:8443/ws`. Configure `BANK_WS_URL`, `BANK_WS_USERNAME`
and `BANK_PASSWORD` for the intended bank. The HTTP message sender uses JVM trust
and hostname verification. Provision the bank certificate/truststore; disabling
validation is not a supported trust configuration. The client sends the configured
UsernameToken and Timestamp.

The facade does not inherit JWT authorization from Traefik or another service's
security chain. Its production REST policy, trusted TLS deployment and complete
REST-to-SOAP journey remain open. Boundary tests use a controlled SOAP transport;
they prove validation/mapping rather than a deployed bank connection.

Sequential identical transfer retries use the local idempotency cache; a changed
payload under the same key returns conflict. This is not durable, atomic or shared
between replicas. Circuit-breaker/retry settings exist, but backend fault injection
and accepted retry classification/half-open recovery remain work. See
[banking verification](../docs/banking-verification.md) and
[contract evolution](../docs/contract-evolution.md) for current evidence and frozen
WSDL/XSD compatibility.
