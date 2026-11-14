# Public API consumer

This standalone Python consumer compiles from the six published OpenAPI 3.1
artifacts and GraphQL SDL/scalar contracts. It imports no producer code. Use
Python 3.12 and install the pinned tooling from `scripts/contracts-requirements.lock`.

```sh
python clients/public-api/generate.py
python -m unittest discover -s clients/public-api/tests/unit -v
```

The generated module is `clients/public-api/build/api_client.py`. Add that directory
to your import path, then supply an origin and a token provider. Tokens are resolved
for every request; mutation retries remain the caller's decision.

```python
from api_client import ProductApi, GraphqlApi

products = ProductApi("http://product-service.localhost", token_provider)
page = products.product_list(query={"page": 0, "size": 10}, expected_status=200)
gateway = GraphqlApi("http://graphql.localhost", token_provider)
catalog = gateway.execute("Catalog", variables={"page": 0, "size": 10})
```

REST calls validate declared parameters, bodies, statuses, media types and JSON
schemas. GraphQL calls validate operation variables, selected output fields,
nullability, enums and scalar wire formats. Redirects never forward credentials.
The `operations` directory contains named, compiled query/mutation examples.

For executable authenticated examples through Traefik, provision the isolated
stack and run `export_live.py`, the drift checker, then `run_deployed.py` as
described in [public API acceptance](../../docs/public-api-contracts.md).
Development fixture credentials belong only to that opt-in harness. Configure
real client credentials outside source control.
