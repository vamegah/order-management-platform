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
            params.put("payment_method", "pm_card_visa"); // NOTE: placeholder; see design note
            params.put(
                    "description",
                    request.description() == null
                            ? "Order " + request.orderId()
                            : request.description());
            params.put("metadata[order_id]", request.orderId().toString());
            params.put("metadata[customer_id]", request.customerId().toString());
            params.put("metadata[payment_id]", request.paymentId().toString());

            JsonNode response = apiClient.createPaymentIntent(params);
            return classifyCharge(response);

        } catch (StripeApiException e) {
            log.warn(
                    "Stripe charge failed (status={}, retryable={}): {}",
                    e.getHttpStatus(),
                    e.isRetryable(),
                    e.getMessage());
            if (e.isRetryable()) {
                return ProviderChargeResult.error(e.getMessage());
            }
            return ProviderChargeResult.declined(classifyDeclineCode(e), e.getMessage());
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

            return ProviderWebhookVerification.valid(
                    new ProviderWebhookEvent(eventId, eventType, data, paymentRef));

        } catch (Exception e) {
            log.warn("Failed to parse Stripe webhook payload", e);
            return ProviderWebhookVerification.invalid("unparseable_body");
        }
    }

    // -----------------------------------------------------------------------
    // Response classification
    // -----------------------------------------------------------------------

    private ProviderChargeResult classifyCharge(JsonNode response) {
        String status = response.path("status").asText("");
        String id = response.path("id").asText(null);

        switch (status) {
            case "succeeded":
                return ProviderChargeResult.succeeded(id);
            case "requires_action", "requires_confirmation":
                String url =
                        response.path("next_action")
                                .path("redirect_to_url")
                                .path("url")
                                .asText(null);
                return ProviderChargeResult.requiresAction(url, id);
            case "requires_payment_method":
                String declineCode =
                        response.path("last_payment_error")
                                .path("decline_code")
                                .asText("card_declined");
                String message =
                        response.path("last_payment_error")
                                .path("message")
                                .asText("Payment method declined");
                return ProviderChargeResult.declined(declineCode, message);
            case "canceled":
                return ProviderChargeResult.declined("canceled", "Payment canceled");
            default:
                // processing, requires_capture -> treat as error so the caller retries
                return ProviderChargeResult.error("PaymentIntent status: " + status);
        }
    }

    private static String classifyDeclineCode(StripeApiException e) {
        JsonNode body = e.getBody();
        if (body != null) {
            JsonNode error = body.path("error");
            String code = error.path("decline_code").asText(null);
            if (code == null || code.isBlank()) {
                code = error.path("code").asText(null);
            }
            if (code != null && !code.isBlank()) {
                return code;
            }
        }
        return "provider_declined";
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
