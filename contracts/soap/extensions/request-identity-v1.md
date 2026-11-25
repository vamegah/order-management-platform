# Durable SOAP request identity v1

Owner: banking boundary. Namespace: `urn:omp:banking:request:v1`.
The `RequestId` header contains 1–128 ASCII letters, digits, dots, underscores,
colons or hyphens and targets the ultimate receiver. Adapter transfers always
set SOAP 1.1 `mustUnderstand="1"`.

Understanding this header requires atomic, durable duplicate detection together
with the transfer's debit, credit and terminal outcome. Reuse with the same
canonical payload returns the original result, including its timestamp and
balance. A changed payload faults with `IDEMPOTENCY_CONFLICT`. Business declines
remain declines when retried. Keys/results never expire automatically. Process
restarts, credential changes and CA changes preserve the same logical platform
namespace. Unrelated banking clients require a separate authenticated namespace.

This envelope extension does not change the frozen v1 body WSDL/XSD. Compliant
receivers lacking these semantics must reject the mandatory header before
processing the body. Never remove the mandatory attribute to enable a provider
that lacks duplicate detection. See the [SOAP 1.1 processing model](https://www.w3.org/TR/soap11/).

The adapter scopes REST keys by verified issuer/subject and hashes a canonical
payload; ordinary callers cannot choose another administrator's stored identity.
Legacy SOAP callers without a RequestId remain supported but each call is a new
intent. They must reconcile an ambiguous outcome rather than retry it as a new
transfer. The required producer/client tests verify mandatory-header rejection,
real durable duplicate results and preservation of the frozen body contract.
