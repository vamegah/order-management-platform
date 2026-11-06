# Legacy bank simulator

Spring-WS SOAP implementation for the banking contract. Handwritten endpoints
use generated JAXB types and the bank's in-memory simulation; this service is a
reference backend, not a production banking ledger.

The checked-in WSDL is published at `/ws/banking.wsdl` with embedded schema.
UsernameToken PasswordText and Timestamp validation use the configured bank
credentials. The boundary tests reject missing/invalid credentials, digest tokens
and missing timestamps, and verify transfer/reversal/fault behavior. See
[banking verification](../docs/banking-verification.md) and the
[frozen contracts](../contracts/soap/v1).

```sh
./mvnw -pl legacy-bank-service -am -Pstatic-analysis clean verify
docker build -f legacy-bank-service/Dockerfile -t omp/legacy-bank-service:local .
```

[Base configuration](src/main/resources/application.yml) selects HTTPS on 8443
and a development `classpath:keystore.p12`. Provision a matching store or supply
`SERVER_SSL_KEY_STORE`, `SERVER_SSL_KEY_STORE_PASSWORD`, `SERVER_SSL_KEY_ALIAS`
and `BANK_PASSWORD` through the deployment's secret mechanism. The adapter must
trust that issuer and hostname. Do not infer that a keystore was distributed or
trusted because a Docker image built successfully. The accepted local SOAP tests
use their explicit HTTP fixture; deployed trusted TLS and rotation remain open.

JAXB generation, producer/client schema parity and exact PR-base drift checks
are required by the [contract policy](../docs/contract-evolution.md). Preserve
v1 operations, actions, fault names and namespaces. There is no deployed
REST-to-adapter-to-bank acceptance claim yet; [TASKS.md](../TASKS.md) tracks the
remaining TLS, topology, resilience and production data work.
