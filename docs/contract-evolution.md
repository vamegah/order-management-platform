# Protocol ownership and compatibility

The inventory owner reviews `shared/proto/src/main/proto/inventory`, the payment
owner reviews `shared/proto/src/main/proto/payment`, and both owners review
`common`. The banking owner reviews both banking modules and `contracts/soap`.
The reviewer for a calling service must also review changes to its consumed
contract. Repository administrators map these responsibilities to their GitHub
teams and require the `quality-gate` status in branch protection.

## Protobuf

The required `contract-compatibility` workflow job generates Java messages,
gRPC clients and a descriptor set using the pinned generators. Surefire compares
that output with the published v1 descriptor. For pull requests it also generates
the exact base revision's descriptor and compares it with the head. Updating the
head's fixture cannot erase contracts added since the original v1 release.

Existing packages, Java options, messages, nested/map types, field names/numbers,
JSON names, cardinality, presence, oneof names/membership, defaults, enums,
reservations and RPC input/output/streaming modes remain compatible. New fields,
messages, enum values and RPCs may be additive. Producers must not require new
fields from existing clients. Consumers preserve unknown fields and handle
unknown enum values, as verified by the generated-client tests.

Never reuse a field number or name. Deprecate published fields in place rather
than deleting them. If an unpublished field is removed, reserve both its number
and name; preserve enum reservations too. New incompatible APIs use a new package
(for example `omp.inventory.v2`) while retaining the existing `omp.inventory`
API for its consumers. Appending or reordering oneofs can change generated
clients; keep existing declarations and membership stable.

Run locally from the repository root:

```sh
./mvnw -pl shared/proto -am clean verify
./mvnw -pl shared/proto -am clean verify \
  -Dproto.compatibility.baseline=/absolute/path/to/base/platform.protobin
```

The published fixture is checked in both commands. A configured but missing PR
base file fails verification. Descriptors are attached as Maven artifacts and
uploaded with test reports by CI. These checks verify wire/client evolution;
transport, authorization and load testing remain separate gates.

## SOAP

`contracts/soap/v1` freezes the existing banking WSDL and XSD. Both the bank and
adapter retain matching copies. The bank publishes that WSDL at
`/ws/banking.wsdl`, with its schema embedded so clients do not need a second,
unmapped schema URL. JAXB regenerates from the adapter WSDL and the bank XSD on
every clean build. The HTTP boundary test checks published SOAP actions, fault
declarations and the embedded namespace.

```sh
python scripts/check_soap_contracts.py
python scripts/check_soap_contracts.py --base-root /path/to/pr-base
./mvnw -pl legacy-bank-service,legacy-banking-adapter -am clean verify
```

The Python gate compares XML structure with QName resolution. Prefixes,
whitespace, annotations and deployment addresses may change. The effective
schema of an older locally imported WSDL compares equally with the embedded
schema. Types, element ordering/cardinality, messages, operations, SOAP actions
and fault bindings are frozen for v1. This conservative policy requires a new
version for other structural changes, including additions. Keep v1 supported
while rolling out a new namespace and endpoint.

CI checks the head fixtures against the exact PR base as well as comparing all
current copies and embedded schemas. Replacing every head copy and fixture still
fails against the base. Missing/malformed contracts fail closed. The workflow
then regenerates JAXB and runs the actual bank/adapter test suites. GitHub
execution and repository branch protection require evidence from that repository;
a local passing script alone does not establish merge protection.
