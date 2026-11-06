# Product and customer batch reads

`GET /api/v1/products/batch?ids=<uuid>&ids=<uuid>` and
`GET /api/v1/customers/batch?ids=<uuid>&ids=<uuid>` return JSON arrays of the
same DTOs as the single-resource endpoints. Both endpoints require a JWT.

Requests accept 1–100 IDs, including duplicates. Found resources appear in the
order of their first occurrence in the request. Missing IDs and duplicate IDs
are omitted. An entirely missing batch returns `[]`, with status 200. Invalid
UUIDs, empty batches and batches over 100 IDs return 400. The bound applies
before deduplication and before database access.

Product batches are available to authenticated callers. Customer batches are
available to administrators; other callers may request exactly one ID matching
their JWT's `customer_id`. A batch containing another customer's ID is rejected
as a whole with 403, without querying customer data. The gateway forwards the
caller's JWT and cannot bypass this rule.

GraphQL's product and customer DataLoaders deduplicate IDs per request and send
one HTTP request per service for each batch of at most 100 IDs. Returned DTOs
are matched by ID, independently of response order. Missing IDs resolve to null;
GraphQL's declared nullability determines any resulting error propagation.
Downstream failures remain errors rather than becoming empty results.

Regression coverage includes request ordering, missing IDs, deduplication,
oversized requests, customer authorization and a GraphQL query with repeated
and distinct product IDs that must produce one downstream request.
