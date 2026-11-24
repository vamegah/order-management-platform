package com.example.omp.payment.provider.stripe;

import com.example.omp.payment.provider.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "payment.provider", havingValue = "stripe")
public class StripePaymentProvider implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(StripePaymentProvider.class);

    private final StripeApiClient apiClient;
    private final StripeWebhookVerifier webhookVerifier;
    private final ObjectMapper objectMapper;

    public StripePaymentProvider(
            StripeApiClient apiClient,
            StripeWebhookVerifier webhookVerifier,
            ObjectMapper objectMapper) {
        this.apiClient = apiClient;
        this.webhookVerifier = webhookVerifier;
        this.objectMapper = objectMapper;
    }

    @Override
    public String providerName() {
        return "stripe";
    }

    @Override
    public ProviderChargeResult charge(ChargeRequest request) {
        try {
            Map<String, String> params = StripeApiClient.params();
            params.put("amount", Long.toString(request.amountMinor()));
            params.put("currency", request.currency().toLowerCase());
            params.put("confirm", "true");
            params.put("capture_method", "automatic");
            if (request.paymentMethodId() == null
                    || !request.paymentMethodId().matches("pm_[A-Za-z0-9_]{1,240}"))
                return ProviderChargeResult.error("Tokenized payment method required");
            params.put("payment_method", request.paymentMethodId());
            params.put("automatic_payment_methods[enabled]", "true");
            params.put("automatic_payment_methods[allow_redirects]", "never");
            params.put("use_stripe_sdk", "true");
            params.put(
                    "description",
                    request.description() == null
                            ? "Order " + request.orderId()
                            : request.description());
            params.put("metadata[order_id]", request.orderId().toString());
            params.put("metadata[customer_id]", request.customerId().toString());
            params.put("metadata[payment_id]", request.paymentId().toString());

            JsonNode response =
                    request.providerReference() == null
                            ? apiClient.createPaymentIntent(
                                    params, "omp-charge-" + request.paymentId())
                            : apiClient.getPaymentIntent(request.providerReference());
            if (!matchesCharge(request, response))
                return ProviderChargeResult.error("Provider payment response mismatch");
            return classifyCharge(response);

        } catch (StripeApiException e) {
            log.warn(
                    "Provider charge unresolved (status={}, retryable={})",
                    e.getHttpStatus(),
                    e.isRetryable());
            // Only a fully correlated terminal intent proves a decline. Authentication,
            // validation and conflict errors do not establish a financial outcome.
            JsonNode intent =
                    e.getBody() == null ? null : e.getBody().path("error").path("payment_intent");
            if (e.getHttpStatus() == 402
                    && intent != null
                    && matchesCharge(request, intent)
                    && "requires_payment_method".equals(intent.path("status").asText()))
                return classifyCharge(intent);
            return ProviderChargeResult.error("Provider outcome unresolved");
        }
    }

    @Override
    public ProviderChargeResult cancelCharge(ChargeRequest request) {
        if (request.providerReference() == null)
            return ProviderChargeResult.error("Provider reference pending");
        try {
            JsonNode response = apiClient.getPaymentIntent(request.providerReference());
            if (!matchesCharge(request, response))
                return ProviderChargeResult.error("Provider intent mismatch");
            String status = response.path("status").asText();
            if (!java.util.Set.of("succeeded", "canceled").contains(status))
                response =
                        apiClient.cancelPaymentIntent(
                                request.providerReference(), "omp-cancel-" + request.paymentId());
            if (!matchesCharge(request, response))
                return ProviderChargeResult.error("Provider intent mismatch");
            return switch (response.path("status").asText()) {
                case "succeeded" -> ProviderChargeResult.succeeded(request.providerReference());
                case "canceled" ->
                        new ProviderChargeResult(
                                ProviderChargeResult.Status.DECLINED,
                                request.providerReference(),
                                "provider_canceled",
                                "Provider cancellation verified",
                                null);
                default -> ProviderChargeResult.pending(request.providerReference());
            };
        } catch (RuntimeException unresolved) {
            return ProviderChargeResult.error("Provider cancellation unresolved");
        }
    }

    @Override
    public ProviderRefundResult refund(RefundRequest request) {
        try {
            Map<String, String> params = StripeApiClient.params();
            params.put("payment_intent", request.providerReference());
            params.put("amount", Long.toString(request.amountMinor()));
            if (request.reason() != null) {
                params.put("reason", mapRefundReason(request.reason()));
            }
            params.put("metadata[payment_id]", request.paymentId().toString());

            if (request.idempotencyKey().startsWith("omp-refund-"))
                params.put(
                        "metadata[refund_id]",
                        request.idempotencyKey().substring("omp-refund-".length()));
            JsonNode response = apiClient.createRefund(params, request.idempotencyKey());
            return classifyRefund(request, response);
        } catch (StripeApiException e) {
            // An HTTP error is not a terminal refund outcome. In particular an
            // idempotency conflict may mean the original request is still running.
            return ProviderRefundResult.error("Provider refund request unresolved");
        }
    }

    @Override
    public ProviderRefundResult lookupRefund(RefundRequest request, String providerRefundId) {
        return readRefund(request, providerRefundId, null);
    }

    @Override
    public ProviderRefundResult lookupRefund(
            RefundRequest request, String providerRefundId, java.util.UUID localRefundId) {
        if (localRefundId == null
                || !("omp-refund-" + localRefundId).equals(request.idempotencyKey()))
            return ProviderRefundResult.error("Refund intent identity mismatch");
        return readRefund(request, providerRefundId, localRefundId);
    }

    private ProviderRefundResult readRefund(
            RefundRequest request, String providerRefundId, java.util.UUID localRefundId) {
        try {
            JsonNode response = apiClient.getRefund(providerRefundId);
            if (!providerRefundId.equals(response.path("id").asText("")))
                return ProviderRefundResult.error("Provider refund identity mismatch");
            if (localRefundId != null
                    && (!localRefundId
                                    .toString()
                                    .equals(response.path("metadata").path("refund_id").asText(""))
                            || !request.paymentId()
                                    .toString()
                                    .equals(
                                            response.path("metadata")
                                                    .path("payment_id")
                                                    .asText(""))))
                return ProviderRefundResult.error("Provider refund intent metadata mismatch");
            return classifyRefund(request, response);
        } catch (StripeApiException e) {
            return ProviderRefundResult.error("Provider refund lookup unresolved");
        }
    }

    private ProviderRefundResult classifyRefund(RefundRequest request, JsonNode response) {
        if (!response.path("amount").isIntegralNumber()
                || !response.path("amount").canConvertToLong()
                || response.path("amount").longValue() != request.amountMinor()
                || !request.currency().equalsIgnoreCase(response.path("currency").asText(""))
                || !request.providerReference().equals(response.path("payment_intent").asText("")))
            return ProviderRefundResult.error("Provider refund response mismatch");
        JsonNode status = response.get("status");
        if (status != null && "succeeded".equals(status.asText())) {
            return ProviderRefundResult.succeeded(response.path("id").asText());
        }
        if (status != null
                && ("failed".equals(status.asText()) || "canceled".equals(status.asText()))) {
            return ProviderRefundResult.failed(
                    response.path("id").asText(), response.path("failure_reason").asText("failed"));
        }
        if (status != null
                && ("pending".equals(status.asText()) || "requires_action".equals(status.asText())))
            return ProviderRefundResult.pending(response.path("id").asText());
        return ProviderRefundResult.error("Refund status: " + status);
    }

    @Override
    public ProviderWebhookVerification verifyWebhook(String rawBody, Map<String, String> headers) {
        StripeWebhookVerifier.VerificationResult result = webhookVerifier.verify(rawBody, headers);

        if (!result.valid()) {
            return ProviderWebhookVerification.invalid(result.reason());
        }

        try {
            JsonNode root = objectMapper.readTree(rawBody);
            String eventId = root.path("id").asText(null);
            String eventType = root.path("type").asText(null);
            JsonNode data = root.path("data").path("object");

            if (eventId == null || eventType == null) {
                return ProviderWebhookVerification.invalid("missing_event_id_or_type");
            }

            String paymentRef = extractPaymentReference(eventType, data);

            if (!eventId.matches("evt_[A-Za-z0-9_-]{1,240}")
                    || eventType.length() > 128
                    || !data.isObject()
                    || paymentRef == null
                    || paymentRef.length() > 255)
                return ProviderWebhookVerification.invalid("invalid_event_contract");
            var safe = objectMapper.createObjectNode();
            for (String field :
                    java.util.List.of(
                            "id",
                            "status",
                            "amount",
                            "currency",
                            "payment_intent",
                            "failure_reason",
                            "last_payment_error"))
                if (data.has(field)) safe.set(field, data.get(field));
            var metadata = safe.putObject("metadata");
            for (String field :
                    java.util.List.of("payment_id", "refund_id", "order_id", "customer_id"))
                if (data.path("metadata").has(field))
                    metadata.set(field, data.path("metadata").get(field));
            return ProviderWebhookVerification.valid(
                    new ProviderWebhookEvent(eventId, eventType, safe, paymentRef));

        } catch (Exception e) {
            log.warn("Provider webhook JSON contract rejected");
            return ProviderWebhookVerification.invalid("unparseable_body");
        }
    }

    // -----------------------------------------------------------------------
    // Response classification
    // -----------------------------------------------------------------------

    private static boolean matchesCharge(ChargeRequest request, JsonNode response) {
        JsonNode method = response.path("payment_method");
        // Stripe clears the active method after a decline. The failed attempt's
        // token remains inside last_payment_error; it cannot prove a capture.
        if ((method.isNull() || method.isMissingNode())
                && "requires_payment_method".equals(response.path("status").asText()))
            method = response.path("last_payment_error").path("payment_method").path("id");
        return response.path("id").asText("").matches("pi_[A-Za-z0-9_]{1,240}")
                && (request.providerReference() == null
                        || request.providerReference().equals(response.path("id").asText()))
                && response.path("amount").isIntegralNumber()
                && response.path("amount").canConvertToLong()
                && response.path("amount").longValue() == request.amountMinor()
                && request.currency().equalsIgnoreCase(response.path("currency").asText(""))
                && request.paymentId()
                        .toString()
                        .equals(response.path("metadata").path("payment_id").asText(""))
                && request.orderId()
                        .toString()
                        .equals(response.path("metadata").path("order_id").asText(""))
                && request.customerId()
                        .toString()
                        .equals(response.path("metadata").path("customer_id").asText(""))
                && (request.paymentMethodId() == null
                        || request.paymentMethodId().equals(method.asText("")));
    }

    private ProviderChargeResult classifyCharge(JsonNode response) {
        String status = response.path("status").asText("");
        String id = response.path("id").asText(null);

        switch (status) {
            case "succeeded":
                return ProviderChargeResult.succeeded(id);
            case "requires_action":
                String secret = response.path("client_secret").asText("");
                if (!secret.startsWith(id + "_secret_") || secret.length() > 512)
                    return ProviderChargeResult.error("Provider next action unavailable");
                return ProviderChargeResult.requiresSdk(id, secret);
            case "processing", "requires_confirmation":
                return ProviderChargeResult.pending(id);
            case "requires_payment_method":
                String declineCode =
                        response.path("last_payment_error")
                                .path("decline_code")
                                .asText("card_declined");
                return new ProviderChargeResult(
                        ProviderChargeResult.Status.DECLINED,
                        id,
                        declineCode.matches("[a-zA-Z0-9_]{1,50}")
                                ? declineCode
                                : "provider_declined",
                        "Payment method declined",
                        null);
            case "canceled":
                return ProviderChargeResult.declined("canceled", "Payment canceled");
            default:
                // processing, requires_capture -> treat as error so the caller retries
                return ProviderChargeResult.error("PaymentIntent status: " + status);
        }
    }

    private static String extractPaymentReference(String eventType, JsonNode data) {
        // Most payment events carry the PaymentIntent id as "id"
        // refund.* events carry the refund object; the payment_intent field points
        // back.
        if (eventType.startsWith("refund.")) {
            return data.path("payment_intent").asText(null);
        }
        return data.path("id").asText(null);
    }

    private static String mapRefundReason(String reason) {
        // Stripe accepts: duplicate, fraudulent, requested_by_customer
        return switch (reason) {
            case "duplicate" -> "duplicate";
            case "fraud" -> "fraudulent";
            default -> "requested_by_customer";
        };
    }
}
