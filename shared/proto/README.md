# Shared protocol contracts

This module generates Java protobuf messages and gRPC client/server bindings.
It contains no business implementations or transport credentials.

| Source | Contract |
|--------|----------|
| `common/money.proto` | Decimal-string amount and ISO currency code |
| `inventory/inventory.proto` | Unary reservation/stock APIs, client-streaming bulk reserve, server-streaming updates |
| `payment/payment.proto` | Unary payment/refund APIs and server-streaming payment events |

The current APIs do not declare a bidirectional-streaming RPC. Authentication,
input validation, deadlines, cancellation and flow control are implemented by
services and their callers, not by generated message builders.

## Build and consume

From the repository root:

```sh
./mvnw -pl shared/proto -am clean verify
./mvnw -pl shared/proto -am install
```

The pinned protoc and gRPC generators produce Java sources under
`target/generated-sources/protobuf/java` and `grpc-java`. The plugin registers
source roots and the module packages generated classes and original `.proto`
resources in `omp-shared-proto-<version>.jar`.

The message-generation execution also writes
`target/generated-resources/protobuf/descriptor-sets/platform.protobin`, including
imports, and attaches it as a `protobin` Maven artifact. Tools can inspect this
FileDescriptorSet without Java source generation or a running reflection server.

```java
var amount = com.example.omp.proto.common.Money.newBuilder()
        .setAmount("2499.9900")
        .setCurrency("USD")
        .build();
```

The amount remains a string on the wire. Generated builders do not enforce
monetary precision or currency semantics; application converters must validate
before constructing domain values. Parsing through floating point loses the
purpose of this contract.

## Compatibility tests

Surefire now runs `ProtoCompatibilityTest`; the module no longer disables tests.
The checked-in `src/test/resources/compatibility/v1.protobin` fixture captures the
existing v1 API. Tests compare the newly generated descriptor with this baseline:

- Existing message names, field numbers/names, scalar/message types, cardinality,
  JSON names, presence and oneof membership remain stable.
- Enum names/numbers/defaults and reserved names/ranges remain stable.
- RPC names, request/response types and client/server streaming modes remain stable.
- Java package and outer-class options remain stable.
- Additive messages, methods, fields and enum values are allowed.

Additional tests prove that the guard rejects renumbering and changed streaming
modes, and that generated clients preserve exact money strings, unknown fields
and unknown enum numbers. These are protocol tests, not deployed gRPC authorization
or transport tests.

Existing field numbers must never be reused. Breaking changes need a new package,
such as `omp.inventory.v2`, while retaining v1 for its consumers. Maintain the
baseline as compatibility evidence; replacing it merely to clear a failing check
would remove that protection. The descriptor-generation options are documented
by the [protobuf Maven plugin](https://www.xolstice.org/protobuf-maven-plugin/compile-mojo.html).

The required CI contract job also generates the exact PR base's descriptor and
passes it through `-Dproto.compatibility.baseline`. Both that descriptor and the
published fixture must pass, so replacing a fixture cannot discard compatibility
with newer base-branch fields. Oneof names, map encoding and retained reservations
have explicit regression tests. See [ownership and release rules](../../docs/contract-evolution.md).
