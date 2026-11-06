# Payment-provider simulator

Development HTTP service for scheduling signed success/failure callbacks. It
implements `POST /api/simulate/payment/success` and `/payment/failure`; requests
use the [simulation request/controller](src/main/java/com/example/omp/provider/PaymentSimulationController.java).
A 202 response schedules delivery and does not establish payment-service receipt.

```sh
./mvnw -pl payment-provider-simulator -am -Pstatic-analysis clean verify
docker build -f payment-provider-simulator/Dockerfile -t omp/payment-provider-simulator:local .
```

The application defaults to 8090 and management to 8091. Configure `WEBHOOK_URL`,
`WEBHOOK_HMAC_SECRET`, `WEBHOOK_DELAY_MS` and `FAILURE_RATE` for a dedicated test.
The scheduling bean and real HTTP/signed-receiver cases are implemented and tested.
There is no accepted deployed payment-service/simulator journey yet.

The current base/Compose callback example targets payment's gRPC port 9093 and
`/webhooks/payment`; it is not a working provider HTTP integration. Select the
actual provider-specific REST endpoint and compatible signature/payload contract
before using this topology. Do not point a simulator at a real provider/subscriber
or infer correlation from an arbitrary generated payment-intent ID. Use the real provider in production; do not deploy this simulator or its public
development secrets. Production topology/promotion still needs acceptance.

See [payment verification](../docs/payment-verification.md),
[refund/provider operations](../docs/payment-refunds.md), and
[TASKS.md](../TASKS.md) for the remaining provider adapter, callback correlation,
security and deployed-journey acceptance.
