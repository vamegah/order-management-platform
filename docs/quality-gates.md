# Build and dependency quality gates

> **Status:** complete PR/default-branch gates accepted; production promotion remains separate
> **Last reviewed:** 2026-11-06

The PR/default-branch workflow is `.github/workflows/verify.yml`. Its independent
jobs cover the notification client package, contract compatibility, external deployed journeys, tests/coverage, a packaged payment/notification container journey, PR coverage regression, static analysis, dependency policy/SBOM, and image
builds. It publishes verification artifacts and defines only an isolated
acceptance deployment; production image publication/promotion remains separate. The final `quality-gate`
job requires every job (including the full image matrix) to succeed; failed,
cancelled or skipped prerequisites cannot produce success. Main branch protection
now requires `quality-gate` from GitHub Actions (app ID 15368), strict branch
freshness and administrator enforcement. The configuration was independently
read back on November 6; no additional approving-review requirement was added.

The [complete PR workflow](https://github.com/vamegah/order-management-platform/actions/runs/42334961223)
passes at `e25735e`, including `quality-gate`. Downloaded reports independently
confirm 1,010 reactor cases, twelve deployed service/client cases, both packaged
payment-to-webhook cases, seventeen client transport cases and thirty-two
contract-job cases, without failures, errors or skips. The packaged cases also
run in the reactor; these are separate verification scopes. Exact-base unit
coverage passes for all fourteen production modules. Fifteen complete static
reports contain zero findings/errors/missing classes, all ten source image builds
pass, and the NVD scan analyzes 211 dependencies with no CVSS >= 7 findings.
All eight artifact sets, workflow logs, source commit/tree and file hashes are
retained in `coverage/security-baseline-ci-evidence.zip` and JSON.

The user-approved merge of [PR #2](https://github.com/vamegah/order-management-platform/pull/2)
is `1a38671`. Its [default-branch workflow](https://github.com/vamegah/order-management-platform/actions/runs/42336296845)
also passes every gate, including all 1,010 reactor cases. Its seven artifact sets
and full workflow logs are archived separately in
`coverage/security-baseline-main-ci-evidence.zip` and JSON (push workflows do not
produce the PR-only two-revision coverage artifact). Earlier fixture, bootstrap, scanner, runtime and coverage diagnostics
are preserved in [the implementation report](task-implementation-report.md) and
[security migration](dependency-security-baseline.md). Main protection was
verified before merging; no quality policy was bypassed or reduced.

The required `contract-compatibility` job verifies protobuf descriptors against
the published v1 fixture and the exact PR base, checks SOAP WSDL/XSD parity and
version drift, and regenerates/verifies Java protobuf and JAXB clients. See
[contract evolution](contract-evolution.md) for the compatibility and ownership
policy. The Maven wrapper also verifies its pinned distribution's SHA-256.

`packaged-payment-journey` builds fresh executable jars and runs the self-owned
`PaymentToWebhookContainerIT` suite under `-Pe2e`, requiring its nonempty passing
report. It verifies signed JWT/JWKS authentication and a payment-to-subscriber
handoff across service containers. The independent `verify` job still runs all
default suites and coverage gates. This focused journey does not stand in for
the separately deployed full-stack suites; see [platform verification](platform-verification.md).

The required `external-e2e` job additionally builds the six core service images,
generates a fresh development realm/certificates/database stack, waits for health
checks, and runs every external order/refund/WebSocket suite. The job rejects
missing/skipped reports, retains stack logs and tears down only its run-specific
Compose project. It exercises the checked-in Dockerfiles; local packaged-JAR
runtime probes are recorded separately and do not replace that image-build gate.

The verification job also resolves the production Compose overlay and enforces
[single-replica gRPC streaming](grpc-stream-topology.md), including update and
rollback ordering. Spotless requires LF Java source endings on every platform;
`.gitattributes` uses the same policy.

The required `notification-client` job runs strict TypeScript compilation and
transport regressions, checks npm advisories, emits a CycloneDX SBOM and packages
JavaScript/declarations. The external job additionally runs the package against
the deployed issuer, ownership policy and event pipeline. Artifacts remain local
to CI; registry publication is separate.

## Local commands

With Java 21, Python 3.12+ and Docker available, run from the repository root:

```sh
./mvnw --batch-mode --no-transfer-progress -Pstatic-analysis clean install
python scripts/check_verification.py
python scripts/check_static_analysis.py
./mvnw --batch-mode --no-transfer-progress -Psupply-chain cyclonedx:makeAggregateBom
./mvnw --batch-mode --no-transfer-progress -Psupply-chain dependency-check:aggregate
```

`install` runs the normal verification lifecycle and makes reactor artifacts
available to the subsequent aggregate scanners. Static analysis can also run
independently without starting containers:

```sh
./mvnw -Pstatic-analysis clean test-compile spotbugs:check
python scripts/check_static_analysis.py
python -m unittest discover -s scripts/tests -v
```

The CI dependency job prepares artifacts with `install -DskipTests -DskipITs
-Djacoco.skip=true`. This is preparation for dependency analysis; the independent
`verify` job still requires all unit/container tests and coverage gates. A green
dependency or image job is not test acceptance.

## Test and coverage evidence

Spotless runs at `validate`. Existing JaCoCo instruction thresholds remain 90%
for shared domain, 85% for shared web, and 80% for shared events and services that declare a check.
Both banking modules now declare an 80% check, covering all handwritten production
code and excluding only their generated JAXB package. Existing thresholds and
application-code exclusions in other modules are unchanged.

JaCoCo can skip its check when no execution data exists. After a successful clean
verification, `check_verification.py` rejects missing coverage XML, zero covered
instructions, missing/nonexecuted source suites, and any failed, errored or
skipped tests. It checks the reactor's `*Test`, `*Tests` and `*IT` source classes,
including nested JUnit reports. It does not treat source scaffolding under
pom-packaged aggregators as compiled test suites. Required evidence must come
from a clean build; retained reports from earlier runs are not acceptance evidence.

Default Failsafe verification excludes only the `e2e` tag for suites requiring
a separately running stack. The packaged payment journey owns its infrastructure
and is included in default verification as well as its focused CI job.
`-Pe2e` removes that exclusion without excluding
untagged integration suites. Use `python scripts/check_verification.py
--include-e2e` after full-stack verification. See [test phases](test-phases.md)
for environment requirements; existing full-stack fixtures still need completion.

The script verifies declared coverage modules. Generated proto and test-only
modules do not need production coverage.

## Pull request coverage regression

The required `coverage-regression` job checks every module containing handwritten
Java production sources. It builds the PR merge revision and the exact
`pull_request.base.sha` separately, using clean unit-phase builds with the same
explicit JaCoCo version. It then runs the head's comparison tool against both
reports. A base build, test or report failure fails the job; there is no missing
baseline fallback. In the current source layout, the unit phase needs no Docker.
The independent
`verify` job continues to require container integration tests and absolute gates.

Reproduce the measurement in each checkout:

```sh
./mvnw --batch-mode --no-transfer-progress clean \
  org.jacoco:jacoco-maven-plugin:0.8.12:prepare-agent package \
  org.jacoco:jacoco-maven-plugin:0.8.12:report
python scripts/check_coverage_regression.py snapshot --root . \
  --revision "$(git rev-parse HEAD)" --output coverage/head.json
```

Generate `coverage/base.json` with the same head script's `snapshot` command,
pointing `--root` at the separately built base checkout and recording its commit.
Then compare:

```sh
python scripts/check_coverage_regression.py compare \
  --base coverage/base.json --head coverage/head.json
```

The policy compares exact instruction fractions per module; even a small drop
fails, and another module's improvement cannot offset it. New production modules
need at least 80% unit instruction coverage. Missing baseline modules fail rather
than silently disappearing from the comparison. Deliberate module removal needs
a reviewed policy change. The JSON snapshots record revisions, counts and policy
version; CI publishes both and writes a comparison table to the job summary.

Only generated protobuf/JAXB packages are omitted from this comparison, equally
on both revisions. Application configuration, DTOs and clients remain measured
even where historical absolute gates exclude them. Unit and combined
unit/integration coverage are deliberately separate measurements. Snapshots
reject missing/skipped/failed unit suites, missing XML, malformed counters and
zero covered production instructions. Source class/record/enum files must also
appear in the report, so report exclusions cannot silently hide uncovered
configuration or DTO classes. Empty interfaces need no instruction counters.
Pom-packaged aggregators are skipped because Maven does not compile their
scaffolding sources.
Reports must come from the clean commands
above; snapshots do not authenticate arbitrary externally supplied reports.

The job runs under `pull_request`, without secrets or persisted checkout
credentials. Push/manual runs validate the tooling without a PR-base comparison;
the job itself still must succeed for `quality-gate`. The accepted PR run above
executes the real two-revision comparison against exact base `9bc76d9`.

## Static analysis

`static-analysis` enables SpotBugs 4.10.5.0 with maximum effort, high-confidence
findings and a failing check. Reports are written to each module's
`target/spotbugsXml.xml`. The filter excludes generated protobuf and JAXB packages
only. High-confidence defects must be fixed; broad application-package
exclusions and `continue-on-error` are not part of the policy. Medium/low
confidence findings are outside this initial gate.

## Dependency policy and SBOM

`supply-chain` configures CycloneDX 2.9.3 and OWASP Dependency-Check 13.0.0.
The aggregate CycloneDX 1.6 inventory includes production dependencies and excludes
test-only reactor modules. Outputs are `target/bom.json` and `target/bom.xml`.
Dependency-Check fails on CVSS >= 7.0 and on scanner/update errors. It produces
`target/dependency-check-report.html` and `.json`. There are no vulnerability
suppressions in this initial policy. Existing dependencies may fail the gate;
configuration alone does not establish a clean dependency inventory.

Updates use the official NVD 2.0 JSON feeds, including annual and modified data,
over verified HTTPS. These require no credentials and work for fork PRs. This
avoids the [13.0.0 empty-key API regression](https://github.com/dependency-check/DependencyCheck/issues/8715)
using the [supported data-feed configuration](https://github.com/dependency-check/DependencyCheck/pull/8680).
CI caches the versioned database and checks for updates on every scan
(`nvdValidForHours=0`). The first complete download is larger than an incremental
update. OSS Index is disabled because it requires a separate authenticated
service; NVD scanning remains required. No scan error is converted to success.
An operator choosing the API instead can override `nvdDatafeedUrl` and supply
`NVD_API_KEY` through the environment; keys must never appear in command arguments.

Official configuration references: [SpotBugs violation checks](https://spotbugs.github.io/spotbugs-maven-plugin/examples/violationChecking.html),
[CycloneDX aggregate inventory](https://cyclonedx.github.io/cyclonedx-maven-plugin/),
[Dependency-Check aggregate options](https://dependency-check.github.io/DependencyCheck/dependency-check-maven/aggregate-mojo.html).

## Executable jars and images

The Boot plugin now executes `repackage` during `package`. Each executable module
produces both a plain jar (for reactor dependencies) and a `*-exec.jar` (for
deployment). Dockerfiles build from the repository root using the checked-in
Maven wrapper and `-pl <service> -am`; they can resolve the parent and shared
modules without preinstalled workspace snapshots. They extract only the executable
jar and run the extracted layers as UID 1000. Root `.dockerignore` excludes local
build output, environment files, credentials and private keys.

After packaging, `python scripts/check_packaging.py` verifies every executable
module's plain jar, Boot launcher and actual layer extraction. CI also runs this
check before aggregate dependency analysis. It does not start the services.

```sh
docker build -f order-service/Dockerfile -t omp/order-service:local .
docker compose --env-file .env.example build
```

CI builds all ten executable modules, including the simulator and banking adapter.
Their Dockerfiles do not deploy or expose these services in Compose. The simulator
is a development/test tool. Runtime health, certificate mounts, Compose journeys,
image scanning/signing and production promotion remain separate acceptance work.

The local Docker Desktop engine is reachable through its Windows named pipe,
even though the Docker CLI is absent. Testcontainers is pinned to 1.21.4, whose
[release notes](https://github.com/testcontainers/testcontainers-java/releases/tag/1.21.4)
address recent Docker Engine compatibility. The old import order selected Spring
Boot's 1.19.8 despite the project's 1.20.3 property, and that client failed Docker
29 API negotiation with HTTP 400. The Testcontainers BOM now precedes Spring
Boot's so the selected version takes effect across all modules. Do not interpret
that client error or a missing CLI as proof that the engine is unavailable. Container tests still need
permission to access the Docker socket and pull their images.

Historical local diagnostics and accepted integration results are recorded in
the implementation report. The accepted PR workflow runs a fresh deployed stack;
main protection requires its aggregate gate. Production promotion remains separate.


The static job also requires nonempty reports for every production/protobuf module
and rejects SpotBugs `Error`/`MissingClass` entries, even if its normal check exits
successfully. Gateway analysis supplies GraphQL's referenced JetBrains nullability
annotations through the static-analysis profile. The six evidence-gate regressions
cover missing reports, empty analysis, malformed XML, hidden classpath/errors and unbuilt aggregator scaffolding.


Client JUnit evidence is required independently of the runner exit code. The gate
counts actual testcase elements, rejects failure/error/skip entries, and enforces
the required minimum scope (17 transport and two deployed cases). All 47 Python
tooling regressions pass locally. The generated TLS identity directories are
ignored as complete units, preventing a public certificate with an ignored private
key from breaking fresh checkout provisioning.
