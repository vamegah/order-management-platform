# Published wire contracts

`soap/v1` contains the frozen banking WSDL and XSD. They match the producer and
adapter resources; the WSDL embeds the same XSD. Do not replace these fixtures to
clear a compatibility failure. Follow [contract evolution](../docs/contract-evolution.md)
for ownership, versioning and local/CI checks.

The protobuf v1 descriptor remains in
`shared/proto/src/test/resources/compatibility/v1.protobin`. The generated
descriptor and Java clients are also checked against the exact pull request base.
