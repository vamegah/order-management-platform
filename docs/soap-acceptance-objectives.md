# SOAP acceptance objectives

Declared before deployed measurements (2026-11-24). The disposable topology uses
PostgreSQL, Keycloak, Traefik and independently packaged bank/adapter processes.
No live financial accounts or credentials participate.

* Healthy balance reads: 100 calls, at most eight simultaneous clients, zero
  errors, p95 below 1,500 ms. Preserve the measured runtime CPU/memory in evidence.
* Each backend attempt has a 1 s connect and 2 s read deadline. At most two
  attempts, separated by 100 ms, are allowed for connection/read failures.
  One REST request must finish within 6,500 ms in the failure fixture.
* At most eight concurrent logical backend operations; excess work receives
  stable 503 responses immediately. Business, authentication, TLS and malformed
  response failures never retry. Business failures do not open the breaker.
* At least five completed logical calls and a 50% failure rate open the default
  ten-call-window breaker; at most five consecutive failures suffice in the
  fresh acceptance window. Calls fail fast while
  open; after 15 s, three successful probes close it. Observe both state metrics
  and backend-attempt counts, including half-open probe rejection.
* Concurrent duplicates, lost replies and forced process death must preserve
  the same transaction and result across bank and adapter restarts. Sum of
  ledger balances and the number of completed transfers must remain correct.
* Missing/invalid/digest/passwordless credentials, missing nonce/timestamp,
  expired/future timestamps and exact message replays fail before money moves.
  Replay rejection survives a bank restart. Untrusted CA and hostname mismatch
  fail TLS. Execute credential overlap/removal and CA overlap/removal, including
  successful traffic before, during and after rotation.
* Published REST problem types/statuses cover validation, funds, authentication,
  timeout, backend, open circuit and idempotency conflict. No secret, stack trace
  or backend fault text may appear in REST errors, logs or retained reports.

The required `soap-e2e` CI job retains per-phase results, JUnit, runtime image IDs,
source revision, elapsed times, load percentiles and cleanup status. Acceptance
must reject missing phases, skipped work, dirty CI sources and mismatched images.

Capacity preflight (2026-11-24): the initial 0.75-core bank allocation returned
10 successful duplicate responses and two read deadlines under 12 requests/six
clients. The deployment now permits two bank cores and one adapter core, with
512 MiB each, within the four-core acceptance runner. The objectives above are
unchanged; acceptance records the applied CPU and memory limits with results.
