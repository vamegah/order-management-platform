# Banking boundary verification

> **Status:** Implemented locally; deployed TLS and operational acceptance pending
> **Last reviewed:** 2026-10-29

Both banking modules now measure all handwritten production code and enforce
80% instruction coverage. Only generated `com.omp.bank.soap` JAXB classes are
excluded. They also participate in the per-module PR coverage regression check.
See [quality gates](quality-gates.md) for the separate measurement phases.

The bank application's corrected package enables normal component scanning of
its ledger, SOAP endpoints and security configuration. The adapter's retry
configuration now references the actual exception class, allowing its production
application context to start.

The bank's structured fault resolver now runs before the generic Spring-WS
resolver. Business failures therefore retain their namespaced BankingFault
detail at the real HTTP boundary, matching the existing fault contract.

## SOAP authentication

The bank requires WS-Security UsernameToken and Timestamp. Its user directory
continues to store encoded passwords. PasswordText tokens now use Spring's
PasswordEncoder to verify the incoming password against that encoded value.
Unknown, disabled, locked or expired users fail authentication. Digest, custom
and passwordless token types are rejected. Timestamp validation still requires
a fresh timestamp with a 60-second TTL.

The default WSS4J validator compares PasswordText with the callback's returned
password directly; it does not interpret a Spring password encoding prefix.
The custom validator supplies that integration through WSS4J's validation hook.
See [the WSS4J validator source](https://ws.apache.org/wss4j/xref/org/apache/wss4j/dom/validate/UsernameTokenValidator.html)
and [Spring password storage](https://docs.spring.io/spring-security/reference/6.5/features/authentication/password-storage.html).

PasswordText requires trusted TLS in deployment. The HTTP tests explicitly
disable TLS for local socket verification; they do not establish certificate,
truststore, hostname-validation or rotation acceptance. Existing simulator
credential/keystore defaults remain development settings.

## Executed boundary scope

`BankingSoapBoundaryTest` starts the actual bank application and calls it over a
local HTTP socket using JAXB and WS-Security. It exercises transfer, balance,
reversal, repeated-reversal rejection, structured business faults, WSDL publication,
and missing/invalid credentials, digest tokens, missing timestamps and unknown users.

`BankingAdapterBoundaryTest` starts the actual adapter application and sends REST
requests through its controller, validation, JAXB and configured WS-Security
interceptor. A controlled Spring-WS transport supplies SOAP responses. This
verifies response mapping and validation before upstream calls without claiming
a deployed adapter-to-bank journey.

Sequential retries with an unchanged idempotency key/payload return the cached
transfer response. Reusing that key for another payload now returns HTTP 409
with `urn:problem:idempotency-conflict`, instead of an unhandled server error.
The cache remains in memory and is not an atomic, durable or multi-replica
idempotency guarantee. The broader safe retry/resilience work remains open.

Run the focused boundary tests, static checks and coverage gates with:

```sh
./mvnw --batch-mode --no-transfer-progress \
  -pl legacy-bank-service,legacy-banking-adapter -am -Pstatic-analysis clean verify
```

The implementation report records the executed results. Compose deployment,
trusted TLS, credential/certificate rotation, backend failure injection and
full-stack banking acceptance remain separate backlog requirements.
