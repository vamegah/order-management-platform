# Published wire contracts

The v1 contracts describe the supported public transports and are checked against
both their implementations and the exact pull-request base. A breaking v1 change
requires a new API version while the existing version remains supported.

| Artifact | Producer / surface |
| --- | --- |
| [Product OpenAPI 3.1](openapi/v1/product.json) | Catalog and categories |
| [Customer OpenAPI 3.1](openapi/v1/customer.json) | Profiles, addresses and preferences |
| [Order OpenAPI 3.1](openapi/v1/order.json) | Checkout, lifecycle and administrator recovery |
| [Webhook OpenAPI 3.1](openapi/v1/webhooks.json) | Subscriptions, delivery history and DLQ operations |
| [Payment OpenAPI 3.1](openapi/v1/payment.json) | Charges, refunds, recovery and signed provider callbacks |
| [Banking OpenAPI 3.1](openapi/v1/banking.json) | Administrator REST facade over SOAP |
| [GraphQL SDL](graphql/v1/schema.graphqls) | Public HTTP queries and mutations |
| [GraphQL scalar wire formats](graphql/v1/scalars.json) | Date, DateTime and string-serialized Decimal |
| [Banking WSDL/XSD](soap/v1) | Frozen SOAP v1 boundary |

The reserved `Subscription` declaration is not a supported GraphQL HTTP
transport. Live notifications use the existing STOMP client; subscription work
remains outside the supported v1 HTTP consumer. GraphiQL and introspection are
available only under the documented development policy.

The protobuf v1 descriptor remains in
`shared/proto/src/test/resources/compatibility/v1.protobin`. Generated descriptors
and JAXB clients are independently compared with the exact pull-request base.
See [public API verification](../docs/public-api-contracts.md) and
[contract evolution](../docs/contract-evolution.md).
