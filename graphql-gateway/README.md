# GraphQL Gateway

A GraphQL gateway that aggregates the platform's three REST services
(product-service, customer-service, order-service) into a single,
flexible query interface.

Part of the [Order Management Platform](../README.md).

Queries and mutations are implemented. Product/customer DataLoaders use bounded
batch reads. [Execution limits and production policy](../docs/graphql-production-policy.md)
define depth, weighted complexity, body size, identity rate, response deadlines,
cache behavior, and the full-query-only policy. Subscriptions and order placed-date
filtering remain unfinished; they are not production-supported features.

Clean focused verification passes 59 unit/in-process and eleven real HTTP
endpoint tests with WireMock downstreams, including authentication, customer
mutation authorization, bearer relay and stable conflict/error contracts. The
existing 80% gate passes at 80.49%; deployed cross-service acceptance remains
open. See [the implementation report](../docs/task-implementation-report.md).

## Why GraphQL?

The REST services expose a resource-oriented API. That works well when a
client wants one resource at a time. But real clients often need:

- **A single customer with their recent orders and the products in each.**
  Over REST, that's 4+ calls (customer, orders, then per-line products).
- **Different fields for different clients.** A mobile app wants 5 fields;
  a web dashboard wants 30.
- **Composable filters.** "Active customers in Europe with orders over $1000
  in the last 30 days."

GraphQL lets clients ask for exactly what they need in one round trip.

**Concretely:**

```graphql
query {
  customer(id: "...") {
    firstName
    lastName
    orders(limit: 5) {
      orderNumber
      status
      total { amount currency }
      lines {
        product { name sku }
        quantity
      }
    }
  }
}

```

One query. Data from three services. No client-side orchestration.

Schema
The schema is defined in src/main/resources/graphql/schema.graphqls.
It's the source of truth for the API — read it to understand what's queryable.

Schema structure
Section	Types
Custom scalars	Date, DateTime, Decimal
Enums	CustomerStatus, AddressType, ProductStatus, OrderStatus
Shared types	Money
Customer types	Customer, Address, CustomerPreferences, CustomerPage
Product types	Product, Category, ProductImage, ProductPage
Order types	Order, OrderLine, ShippingAddress, OrderStatusHistory, OrderPage
Root	Query, Mutation, Subscription (declared; resolver pending)
Cross-service fields
Some fields resolve by calling multiple services:

Customer.orders — calls order-service filtered by customer ID.

Order.customer — calls customer-service.

Order.lines[].product — uses a request-scoped product batch loader.

These are what make GraphQL valuable. See DataLoader for
how we avoid N+1 queries when these fields are queried in a list.

Error model
GraphQL returns HTTP 200 even for business errors. Errors appear in the
response body's errors array:

json
{
  "data": { "createCustomer": null },
  "errors": [{
    "message": "A customer with email 'alice@omp.local' already exists",
    "path": ["createCustomer"],
    "extensions": {
      "code": "CONFLICT",
      "traceId": "abc123"
    }
  }]
}
Error codes (mapped from REST statuses):

Code	HTTP Equivalent	Cause
NOT_FOUND	404	Requested resource doesn't exist
CONFLICT	409	Unique constraint violation
VALIDATION_FAILED	400	Invalid input
FORBIDDEN	403	Authenticated but not authorized
UNAUTHENTICATED	401	Missing or invalid JWT
DOWNSTREAM_SERVICE_ERROR	502	A REST service is unavailable
Endpoints
Method	Path	Description
POST	/graphql	The GraphQL endpoint
GET	/graphiql	Browser query editor (dev only)
GET	/actuator/health	Health check
GraphiQL is a full-featured GraphQL IDE. In dev, open
http://graphql-gateway.localhost/graphiql — no setup, no external tools.
It reads the schema, offers autocomplete, and lets you execute queries.

In prod, GraphiQL and schema introspection are disabled. Clients use
their own GraphQL tooling.

Authentication
All GraphQL operations require a JWT in the Authorization header:

text
Authorization: Bearer <jwt>
The gateway validates the JWT (against Keycloak) and forwards it to
downstream services via a Feign interceptor. Downstream services apply
their own authorization rules — the gateway doesn't cache or interpret
permissions.

In GraphiQL, add the header via the "Headers" tab at the bottom of
the editor.

Authorization
Two layers:

Gateway-level — the gateway enforces "authenticated" on all
operations. Public queries (if any) would be explicitly marked.

Field-level — admin-only queries/mutations use @PreAuthorize.
The schema documentation notes which operations require admin.

Downstream services apply their own authorization (e.g., order-service
requires the admin role for shipOrder). The gateway's @PreAuthorize
is the first line of defense; downstream services are the second.

DataLoader
Cross-service fields (e.g., Order.lines[].product) could produce N+1
queries when queried in a list. DataLoader batches them.

How it works: when a query requests products { lines { product } },
instead of calling product-service once per line, the DataLoader collects
all product IDs requested within a single execution tick, fetches them
in one batched call, and distributes the results.

Effect: a query that would make 50 calls makes 2. Latency drops
from "seconds" to "milliseconds" for list queries.

DataLoader is configured in config/DataLoaderConfig.java (Batch 6.4).

Environment Variables
Variable	Default (dev)	Purpose
SPRING_PROFILES_ACTIVE	local	Spring profile
OIDC_ISSUER_URI	http://keycloak.localhost/realms/omp	JWT issuer
OIDC_JWK_SET_URI	http://keycloak:8080/...	JWKS endpoint
PRODUCT_SERVICE_URL	http://product-service:8081	Downstream service
CUSTOMER_SERVICE_URL	http://customer-service:8082	Downstream service
ORDER_SERVICE_URL	http://order-service:8083	Downstream service
OTEL_EXPORTER_OTLP_ENDPOINT	http://otel-collector:4317	Trace exporter
PROMETHEUS_ENV	dev	Metrics environment label
SERVER_PORT	8000	HTTP port
Development
Build
bash
./mvnw -pl graphql-gateway clean package
Run locally
Requires the three REST services to be up:

bash
docker compose up -d postgres keycloak product-service customer-service order-service
./mvnw -pl graphql-gateway spring-boot:run
Then open GraphiQL at http://localhost:8000/graphiql.

Get a JWT
bash
# Alice's token (customer role)
curl -s -X POST http://keycloak.localhost/realms/omp/protocol/openid-connect/token \
  -d "grant_type=password" \
  -d "client_id=postman" \
  -d "username=alice" \
  -d "password=alice" | jq -r '.access_token'
Paste it into GraphiQL's "Headers" tab:

json
{ "Authorization": "Bearer <paste-token-here>" }
Example query
graphql
query {
  products(filter: { categorySlug: "laptops" }, size: 3) {
    content {
      sku
      name
      price { amount currency }
    }
    totalElements
  }
}
Testing
The gateway uses:

spring-graphql-test — GraphQlTester for schema-level tests.

WireMock — stubs for the three downstream services.

REST Assured — end-to-end HTTP tests against /graphql.

See Batch 6.4 for the full test suite.

Observability
Metrics: /actuator/prometheus — HTTP request metrics, Feign client
metrics, GraphQL execution metrics.

Logs: stdout as JSON — Promtail ships to Loki.

Traces: each GraphQL query produces a root span; each downstream
Feign call produces a child span. The full trace is visible in Tempo.

Dashboard: OMP — GraphQL Gateway in Grafana.

Troubleshooting
Query returns errors: [{ message: "No schema" }].

The schema.graphqls file isn't being loaded. Check:

The file is at src/main/resources/graphql/schema.graphqls.

spring.graphql.schema.locations points at classpath:graphql/.

The startup log prints the parsed schema.

Query times out.

A downstream service is slow or unreachable. Check:

bash
docker compose logs product-service customer-service order-service
Circuit breaker state:

promql
resilience4j_circuitbreaker_state{name="productClient"}
DataLoader doesn't batch.

DataLoader batches only within a single execution tick. If your query
resolves products one at a time (e.g., in separate @SchemaMapping
invocations), batching won't occur. Use DataLoader in the resolver that
fetches the list, not per-item.

"Operation not permitted" from GraphiQL.

Browser blocks mixed content. Ensure GraphiQL is served over the same
protocol (http vs https) as the GraphQL endpoint.

Related Documentation
Platform README

Schema file

ADR-0001: Schema-per-service

ADR-0002: Promtail for logs

ADR-0003: Dev + prod overlay
