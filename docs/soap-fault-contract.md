# Banking REST and SOAP fault contract

The administrator facade accepts JWTs from its configured issuer. An absent or
invalid token returns `401 urn:problem:unauthorized`; a verified non-administrator
returns `403 urn:problem:forbidden`. Account ownership is not modeled, so ordinary
customers cannot read any bank account or initiate transfers.

Every error body uses `application/problem+json` with `type`, `title`, `status`
and a fixed public `detail`. The title is the stable uppercase code. Validation
and malformed JSON return `400 urn:problem:invalid-request`. Raw SOAP reasons,
exception messages, account balances, credentials and stack traces never become
public problem details.

| SOAP structured code or condition | HTTP | Problem type suffix |
| --- | --- | --- |
| INVALID_REQUEST | 400 | invalid-request |
| INVALID_ACCOUNT_FORMAT | 400 | invalid-account-format |
| ACCOUNT_NOT_FOUND | 404 | account-not-found |
| IDEMPOTENCY_CONFLICT / conflicting REST key | 409 | idempotency-conflict |
| INSUFFICIENT_FUNDS | 422 | insufficient-funds |
| UNSUPPORTED_CURRENCY | 422 | unsupported-currency |
| DAILY_LIMIT_EXCEEDED | 429 | daily-limit-exceeded |
| WS-Security or TLS authentication failure | 502 | upstream-authentication |
| Unknown/malformed SOAP fault, invalid response, backend fault | 502 | upstream-error |
| Connection failure, open circuit, exhausted capacity, unavailable request store | 503 | upstream-unavailable |
| Exhausted socket read deadline | 504 | upstream-timeout |

Types use the prefix `urn:problem:`. The bank publishes namespaced
`BankingFault` detail with qualified `code` and `message` children, exactly as
in the frozen v1 schema. The adapter reads exact structured codes, never matches
text in a SOAP fault reason. Unknown reversal-only codes also map to 502; reversal
is a SOAP operation and has no public REST route. Authentication faults from the
SOAP authority use a structured UPSTREAM_AUTHENTICATION detail (including the
Spring-WS generic Client fault path). They are upstream failures and do not ask
the REST caller to supply bank passwords.

A timeout never asserts that a transfer failed financially. Retry it with the
same JWT principal, Idempotency-Key and payload. Durable request/result records
and the mandatory SOAP request identity make this safe, even after a lost reply.
See [the runbook](soap-operations.md) for the supported provider protocol and
retention policy.

The OpenAPI document publishes the fixed problem schema and all supported
statuses. Required contract verification compares it with independent live
producer exports and the exact PR base. The WSDL/XSD v1 fixtures remain unchanged.
