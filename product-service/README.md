# Product Service

REST API for the product catalog: products, categories, pricing.

Part of the [Order Management Platform](../README.md). The platform's
top-level README covers the overall architecture; this one covers the
service itself.

## Endpoints

All endpoints are under `/api/v1`. Authentication is via JWT from Keycloak.

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `GET` | `/api/v1/products` | Any | List products with filters and pagination |
| `GET` | `/api/v1/products/{id}` | Any | Fetch a product by ID |
| `GET` | `/api/v1/products/sku/{sku}` | Any | Fetch a product by SKU (case-insensitive) |
| `GET` | `/api/v1/products/search?q=...` | Any | Search products by name |
| `POST` | `/api/v1/products` | Admin | Create a product |
| `PUT` | `/api/v1/products/{id}` | Admin | Update a product's mutable fields |
| `DELETE` | `/api/v1/products/{id}` | Admin | Deactivate a product (soft delete) |
| `GET` | `/api/v1/categories` | Any | List categories |
| `GET` | `/api/v1/categories/{slug}` | Any | Fetch a category by slug |

### Interactive API docs

When running locally:

- **Swagger UI:** http://product-service.localhost/swagger-ui.html
- **OpenAPI spec:** http://product-service.localhost/v3/api-docs

## Environment Variables

| Variable | Default (dev) | Purpose |
|----------|---------------|---------|
| `SPRING_PROFILES_ACTIVE` | `local` | Spring profile |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://postgres:5432/omp?currentSchema=products,public` | JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | `products_svc` | DB role |
| `SPRING_DATASOURCE_PASSWORD` | (from `.env`) | DB password |
| `OIDC_ISSUER_URI` | `http://keycloak.localhost/realms/omp` | JWT issuer |
| `OIDC_JWK_SET_URI` | `http://keycloak:8080/realms/omp/protocol/openid-connect/certs` | JWKS endpoint |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://otel-collector:4317` | Trace exporter |
| `PROMETHEUS_ENV` | `dev` | Metrics environment label |
| `SERVER_PORT` | `8081` | HTTP port |

## Development

### Build

```bash
./mvnw -pl product-service clean package

Run tests
bash
# Unit tests only (fast)
./mvnw -pl product-service test

# Unit + integration tests
./mvnw -pl product-service verify
Run locally
bash
# Requires Postgres and Keycloak from the Docker stack
docker compose up -d postgres keycloak

# Start the service
./mvnw -pl product-service spring-boot:run
Service is at http://localhost:8081.

Run in Docker
bash
# Build the image
docker compose build product-service

# Start (waits for dependencies)
docker compose up -d product-service

# Tail logs
docker compose logs -f product-service

# Health check
curl http://product-service.localhost/actuator/health
Get a JWT for testing
bash
# As alice (customer role — reads only)
curl -s -X POST http://keycloak.localhost/realms/omp/protocol/openid-connect/token \
  -d "grant_type=password" \
  -d "client_id=postman" \
  -d "username=alice" \
  -d "password=alice" | jq -r '.access_token'

# As admin (reads + writes)
curl -s -X POST http://keycloak.localhost/realms/omp/protocol/openid-connect/token \
  -d "grant_type=password" \
  -d "client_id=postman" \
  -d "username=admin" \
  -d "password=admin" | jq -r '.access_token'
Sample request
bash
TOKEN=$(curl -s -X POST http://keycloak.localhost/realms/omp/protocol/openid-connect/token \
  -d "grant_type=password" -d "client_id=postman" \
  -d "username=alice" -d "password=alice" | jq -r '.access_token')

curl -s -H "Authorization: Bearer $TOKEN" \
  "http://product-service.localhost/api/v1/products?size=5" | jq .
Architecture
The service follows a standard layered structure:

text
web/         REST controllers — thin, delegate to services
service/     Business logic, orchestration
repository/  Spring Data JPA
domain/      Entities, value objects
dto/         Wire format (records)
error/       Domain exceptions
config/      Spring configuration
Rules (enforced by ArchUnit tests):

Controllers don't depend on repositories.

Services don't depend on web.*.

Domain classes don't depend on Spring (except auditing annotations).

Observability
Metrics: /actuator/prometheus — Prometheus scrapes every 15s.

Logs: stdout as JSON — Promtail ships to Loki.

Traces: exported via OTLP to the OTel Collector, forwarded to Tempo.

Dashboard: OMP — Product Service in Grafana.

Database
Schema: products in the omp database.

Role: products_svc (owns the schema).

Migrations: Flyway, in src/main/resources/db/migration/.

V1__initial_schema.sql — tables and indexes.

V2__seed_data.sql — development seed data.

Reset the database
bash
docker compose down -v
docker compose up -d
Tests
Type	File pattern	Runs during	Speed
Unit	*Test.java	mvn test	ms
Integration	*IT.java	mvn verify	seconds
Contract	*IT.java	mvn verify	seconds
Architecture	*Test.java	mvn test	ms
Coverage threshold: 80% (enforced by JaCoCo).

Troubleshooting
Service won't start — "Connection refused" to Postgres.

Postgres container isn't healthy. Check:

bash
docker compose ps postgres
docker compose logs postgres
401 Unauthorized on every request.

JWT validation failed. Causes:

Token expired (>5 minutes old).

Wrong issuer — the token's iss claim must match OIDC_ISSUER_URI.

Keycloak unreachable from the service (OIDC_JWK_SET_URI).

Check the service logs:

bash
docker compose logs product-service | grep -i "jwt\|oauth"
403 Forbidden on POST/PUT/DELETE.

Token lacks the admin role. Log in as admin, not alice.

404 on every endpoint.

Traefik isn't routing. Check:

bash
docker compose logs traefik | grep product-service
Verify the labels are present:

bash
docker inspect omp-product-service-1 | jq '.[0].Config.Labels'
Slow queries.

Enable SQL logging in dev:

yaml
# application-local.yml
logging:
  level:
    org.hibernate.SQL: DEBUG
    org.hibernate.orm.jdbc.bind: TRACE
Then watch logs:

bash
docker compose logs -f product-service | grep "select\|insert\|update"
Related Documentation
Platform README — overall architecture.

Phase Plan — what's coming next.

Runbooks — operational procedures.