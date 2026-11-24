# Banking boundary deployment and operations

The bank is a simulated external authority with a durable PostgreSQL ledger.
The adapter is an administrator-only REST facade. Production credentials,
private keys and initial balances are never supplied by the repository.

## Start a fresh development deployment

Run `bash scripts/setup-banking-development.sh`, then use the repository root
Compose build context to build and start `traefik legacy-banking-adapter` with
your normal environment file. Compose starts PostgreSQL, Keycloak and the bank
as dependencies. Setup requires Bash, OpenSSL and JDK keytool; Docker build
regenerates JAXB from committed contracts without host `target` directories.

The route is `http://banking.localhost/api/v1/banking`. Obtain an administrator
JWT from the configured issuer, then POST `/transfers` or GET
`/accounts/ACC-1/balance`. Supply Idempotency-Key for transfer retries. The docker
profile seeds ACC-1/2/3 once with USD 10000/5000/25000 using insert-if-absent;
restarts preserve balances. The legacy transfer cap is USD 10000 per transfer;
there is no foreign-exchange conversion or cross-currency movement. The prod
profile never seeds accounts.

The adapter calls `https://legacy-bank-service:8443/ws` with hostname checking
and a dedicated truststore. The bank has no public port or Traefik route.
Management uses private port 9090, including database readiness and Prometheus.
The ingress route excludes actuator endpoints. The Grafana `OMP Banking SOAP
Boundary` dashboard shows call rate, latency, circuit state, retries, rejected
work and available backend slots. Tags never contain accounts or request IDs.

## Mounted configuration

Each application imports `/run/secrets/bank/` as a Spring configtree. Put each
property value in a same-named file, readable by application UID/GID 1000.
Never pass secret values in command arguments, log output or committed files.

| Mount | Required files |
| --- | --- |
| Bank configtree | bank.security.password, spring.datasource.password, server.ssl.key-store-password |
| Adapter configtree | bank.ws.password, spring.datasource.password, bank.ws.trust-store-password |
| Bank private TLS directory | tls.p12 (PKCS12, alias legacy-bank-service) |
| Adapter public trust directory | truststore.p12 (CA certificates only; no server key) |
| PostgreSQL bootstrap directory | ledger-password, adapter-password (same respective DB passwords, GID 70 for the configured Alpine PostgreSQL image; BANK_POSTGRES_GID overrides setup) |

Optional configtree properties `bank.security.username` and `bank.ws.username`
default to platform-user. The bank also supports `bank.security.previous-username`
and `bank.security.previous-password` during an explicit overlap. Both names
represent the same logical platform client; unrelated principals must not be
added to this directory. Passwords are encoded in the bank's user directory.

The production overlay mounts `/etc/omp/secrets/bank-ledger`,
`/etc/omp/secrets/bank-adapter`, `/etc/omp/secrets/bank-database`,
`/etc/omp/tls/legacy-bank-service` and `/etc/omp/trust/bank`. Override their paths
with BANK_LEDGER_SECRETS_DIR, BANK_ADAPTER_SECRETS_DIR,
BANK_DATABASE_SECRETS_DIR, BANK_TLS_DIRECTORY and BANK_TRUST_DIRECTORY.
Database URLs/users are BANK_DB_URL/BANK_DB_USER and
BANK_ADAPTER_DB_URL/BANK_ADAPTER_DB_USER. BANK_WS_URL selects the trusted HTTPS
endpoint; BANK_TRUST_STORE selects the mounted PKCS12 truststore. No production
password or keystore fallback exists. Do not enable the test-only HTTP waiver.

The role bootstrap creates isolated bank_ledger_svc/bank_adapter_svc roles and
owned schemas. It reads private files and disables statement/duration/error
statement logging for credential installation. On an existing PostgreSQL volume,
apply `infrastructure/postgres/init/03-banking.sh` inside the PostgreSQL container
after mounting the matching bootstrap files; init scripts run automatically only
on a new database volume. Flyway creates each application's tables independently.

## Upgrade from the prototype

Drain and stop writes through the old in-memory facade before enabling this
version. Its old cache and simulator balances have no durable history to migrate.
Reconcile any old ambiguous transfer directly with its authority; never submit it
as a new intent. Once the durable bank/adapter pair is active, replicas share the
same request stores. The guarantees below apply to intents admitted by that pair.

## Safe transfer identity and recovery

The REST key is hashed with issuer and subject. Its normalized payload hash
conflicts with another payload and is independent of incidental decimal scale.
The adapter reserves a durable row before calling SOAP and never holds a database
transaction across the network. Concurrent pending calls reuse the same bank ID.
The bank commits the request hash, terminal result and both balance changes in
one transaction. Account locks use a stable order. Declines are durable too;
adding funds later does not turn the same declined intent into another transfer.

The SOAP extension `{urn:omp:banking:request:v1}RequestId` is mandatory on adapter
transfers (`mustUnderstand=1`). Its semantics require durable duplicate detection,
payload conflict detection and replay of the original result. A compliant older
backend that lacks this extension must fault before processing the transfer.
The bank explicitly understands it. This is an envelope extension; the frozen
v1 WSDL/XSD and legacy unkeyed clients remain supported. An external provider must
implement this protocol before it can serve adapter transfers. Never remove the
mandatory marker to make an incompatible provider accept money movement.
See [SOAP 1.1 mandatory headers](https://www.w3.org/TR/soap11/#_Toc478383500).

Only connection/read failures retry, at most twice with 100 ms between attempts.
Default connect/read deadlines are 1/2 seconds. TLS, authentication, business and
malformed-response failures never retry. Business failures do not affect the
breaker. With at least five completed calls in a ten-call window and a failure rate of
50% or more, the breaker opens for 15 seconds; three
successful half-open probes close it. A bulkhead permits eight logical calls and
immediately rejects excess work with 503. Metrics expose these decisions.

Pending records survive process death. A subsequent call with the same identity
retrieves the bank's committed outcome or safely performs the single transfer.
Never delete pending rows to clear errors. Retain bank and adapter identities and
results indefinitely, including reversals and declines. Archive only through a
migration that preserves a durable identity/result or refusal tombstone; deleting
old keys permits old retries to move money again. Back up both schemas and restore
them consistently. A missing REST key creates a fresh intent for each request.

## WS-Security and rotation

PasswordText is permitted only with trusted TLS. Each message requires a fresh
UsernameToken Created, random nonce and Timestamp. Digest/passwordless/custom
passwords are rejected. The maximum age is 60 seconds and future allowance is
five seconds. Consumed nonce hashes remain in PostgreSQL for 120 seconds, making
replay rejection atomic across replicas and restarts. A fresh retry generates a
new nonce and timestamp while preserving the durable transfer identity.

For credential rotation, mount a distinct new username/password as current on
the bank and the old pair as previous, then restart the bank. Verify the old
adapter still succeeds. Switch the adapter to the new pair and restart it, verify
traffic, then remove the previous pair and restart the bank. Verify the new pair
succeeds and the old pair fails. Keep the previous files privately until rollback
is no longer needed. Single-instance restarts can briefly return bounded 503;
use rolling replicas when uninterrupted service is required.

For a CA change, first add both CAs to the adapter's public truststore and restart
it; verify the old server. Replace the bank's certificate/key PKCS12 with the new
identity (correct SAN/EKU and chain), restart it, and verify new trusted traffic.
Remove the retired CA only after every bank instance has switched, restart the
adapter, and verify old trust/hostname mismatches fail. Mount directories so atomic
file replacement is visible; mounting one file can pin an old inode. Passwords
for the server keystore and client truststore are separate. Never use a permissive
trust manager, hostname verifier, or no-check-certificate health probe.

Rollback a credential/certificate change while both trust/credential generations
overlap. A failed restart should be resolved by restoring the matching prior
files and restarting; it must not be resolved by disabling TLS or WS-Security.

## Executed acceptance and failure controls

`docker-compose.soap-e2e.yml` is disposable acceptance only. Run
`bash scripts/setup-soap-acceptance.sh`, build both banking images with VCS_REF,
start the stack with a unique COMPOSE_PROJECT_NAME beginning
`omp-soap-acceptance-`, then run `python scripts/verify_soap_boundary.py --project
<that-name>`. Use the base, e2e and soap-e2e Compose files together. Tear down that
project with `down --volumes --remove-orphans` afterward.

The bank's simulator control table is read only when explicitly enabled in the
acceptance overlay. There is no HTTP control route. The harness changes backend
fault/latency/lost-reply controls through the disposable PostgreSQL service,
forces process death, executes both rotations and verifies bounded load. It
restores modified files and resets controls before teardown. Private fixture
material stays under ignored `coverage/soap-fixtures`; retained evidence is
limited to `coverage/soap-acceptance` and redacts generated secrets. Required CI
checks source revision, runtime images, all seven phases, JUnit and cleanup.
See [the predeclared objectives](soap-acceptance-objectives.md).
