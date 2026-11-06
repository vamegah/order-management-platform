package com.example.omp.payment.provider;

/**
 * Vendor-neutral payment provider interface.
 *
 * <p>Implementations: StripePaymentProvider (prod), NoopPaymentProvider (dev/test).
 *
 * <p>All methods are synchronous and must be safe to retry with the same idempotency key. The
 * provider is responsible for honoring idempotency at the vendor boundary.
 */
public interface PaymentProvider {

    /** Short identifier matching the DB CHECK constraint. */
    String providerName();

    /**
     * Charges (authorize + capture in one step). Idempotent on {@code request.idempotencyKey()}.
     */
    ProviderChargeResult charge(ChargeRequest request);

    /** Refunds a previously captured payment. Idempotent on {@code request.idempotencyKey()}. */
    ProviderRefundResult refund(RefundRequest request);

    /** Reads an existing provider refund; implementations must never create a refund here. */
    default ProviderRefundResult lookupRefund(RefundRequest request, String providerRefundId) {
        return ProviderRefundResult.error("Refund lookup unsupported");
    }

    /**
     * Strict operator discovery: additionally prove that provider metadata identifies this intent.
     */
    default ProviderRefundResult lookupRefund(
            RefundRequest request, String providerRefundId, java.util.UUID localRefundId) {
        return ProviderRefundResult.error("Verified refund discovery unsupported");
    }

    /**
     * Verifies an inbound webhook. Returns a result that either confirms authenticity (and yields
     * the parsed event) or rejects it.
     */
    ProviderWebhookVerification verifyWebhook(
            String rawBody, java.util.Map<String, String> headers);
}
