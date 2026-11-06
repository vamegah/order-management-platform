# Dedicated development acceptance

Use this for a local/CI reference stack. The latest exact passing scope and
source-bound evidence are in [the implementation report](../task-implementation-report.md).
It is not a production deployment or a data-migration procedure.

1. Use Java 21, Python 3.11+ (CI 3.12), Node 24, Bash/OpenSSL and Docker Compose
   2.24.4+. Allocate at least 6 GB to the core stack. Run the full default reactor
   separately from a resource-limited external stack.
2. From the repository root, copy `.env.example` to `.env`, review development
   values, and select a project name that belongs only to this acceptance run:

   ```sh
   export COMPOSE_PROJECT_NAME=omp-acceptance-local
   bash scripts/ci-setup.sh
   docker compose -f docker-compose.yml -f docker-compose.e2e.yml config --quiet
   ```

   Certificate setup preserves existing identities and fails on mismatched,
   incomplete or expired files. Do not delete certificates/volumes to hide a
   setup error. On Linux, grant container UID/GID 1000 read access to mounted leaf
   keys while keeping the CA private key owner-only. See [exact setup](../external-e2e.md).
3. Build/start the six core services and require health checks:

   ```sh
   docker compose -f docker-compose.yml -f docker-compose.e2e.yml \
     up -d --build --wait --wait-timeout 600 traefik \
     product-service customer-service order-service \
     inventory-service payment-service notification-service
   docker compose -f docker-compose.yml -f docker-compose.e2e.yml ps -a
   ```

   Check ingress readiness too: a container can become healthy before Traefik's
   provider refresh exposes its route. Investigate 404/502, issuer, database role,
   broker and TLS errors before any order-creation POST. A fresh realm supplies
   `sub`, roles and the managed customer mapping. Existing realms do not update
   through normal boot imports.
4. Run independent external journeys and reject missing/skipped suites:

   ```sh
   ./mvnw -pl integration-tests -am -Pe2e verify
   python scripts/check_verification.py --root integration-tests --include-e2e
   cd clients/notifications
   npm ci --ignore-scripts
   npm test
   npm run test:deployed
   cd ../..
   ```

   These cases mutate newly created orders and one seeded stock item; use a
   dedicated environment. Host gRPC clients need the original CA and client key.
   If a host TLS interceptor substitutes certificates, use an isolated Linux test
   runner; retain certificate validation and route REST/WebSocket through Traefik.
5. Preserve logs, actual JUnit/Failsafe reports, source/configuration hashes and
   image/JAR identities before rebuilding. A report from another revision is not
   acceptance of changed code. Stop only this project to free resources, retaining
   volumes unless a separately reviewed disposal is intended:

   ```sh
   docker compose -f docker-compose.yml -f docker-compose.e2e.yml stop
   ./mvnw -Pstatic-analysis clean verify
   python scripts/check_verification.py
   python scripts/check_static_analysis.py
   python scripts/check_packaging.py
   ```

The default reactor starts its own test infrastructure and packaged journey. Both
that command and the separately deployed journeys must pass. Never use skipped
container cases, reduced coverage thresholds or a disabled TLS validator as
acceptance. On Windows, the test process needs Docker named-pipe access and Maven
must be able to remove its generated `target` directories. Limit any attribute or
cleanup operation to verified workspace build outputs.
