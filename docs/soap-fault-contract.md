# Banking SOAP fault contract

The bank emits a `BankingFault` element inside SOAP Fault `detail`. Its namespace
is `http://legacybank.example.com/ws`. The qualified `code` and `message` children
follow `legacy-bank-service/src/main/resources/xsd/banking.xsd`.

The adapter uses the exact structured code, never substrings in the fault reason.
Unknown codes, missing details, and malformed details map to `UPSTREAM_ERROR`
(HTTP 502). Business codes map to the statuses tested by
`BankingExceptionHandlerTest`. The REST response never includes a raw SOAP reason
or a transport exception message. Infrastructure faults use the public message
`Bank service unavailable`.

Run the independent boundary checks with:

```sh
./mvnw -pl legacy-bank-service,legacy-banking-adapter clean test
```

HTTPS uses the JVM trust configuration. Supply the bank CA using
`javax.net.ssl.trustStore` and its password through the deployment secret store.
Hostname verification remains enabled. Replace the truststore and restart the
adapter during certificate rotation; zero-downtime rotation is not yet verified.
