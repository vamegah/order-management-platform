package com.example.omp.payment.provider;

/**
 * Outcome of a charge attempt.
 *
 * <p>status: SUCCEEDED - charge captured at the provider DECLINED - provider declined (card,
 * insufficient funds, etc.) REQUIRES_ACTION - 3DS or similar; caller must redirect the customer
 * ERROR - network/provider error; safe to retry
 */
public record ProviderChargeResult(
        Status status,
        String providerReference,
        String declineCode,
        String declineMessage,
        String requiresActionUrl,
        String clientSecret) {
    public ProviderChargeResult(
            Status status, String ref, String code, String message, String url) {
        this(status, ref, code, message, url, null);
    }

    @Override
    public String toString() {
        return "ProviderChargeResult[status=" + status + ", reference=" + providerReference + "]";
    }

    public static ProviderChargeResult pending(String ref) {
        return new ProviderChargeResult(Status.PENDING, ref, null, null, null);
    }

    public static ProviderChargeResult requiresSdk(String ref, String secret) {
        return new ProviderChargeResult(Status.REQUIRES_ACTION, ref, null, null, null, secret);
    }

    public enum Status {
        SUCCEEDED,
        DECLINED,
        REQUIRES_ACTION,
        PENDING,
        ERROR
    }

    public static ProviderChargeResult succeeded(String ref) {
        return new ProviderChargeResult(Status.SUCCEEDED, ref, null, null, null);
    }

    public static ProviderChargeResult declined(String code, String message) {
        return new ProviderChargeResult(Status.DECLINED, null, code, message, null);
    }

    public static ProviderChargeResult requiresAction(String url, String ref) {
        return new ProviderChargeResult(Status.REQUIRES_ACTION, ref, null, null, url);
    }

    public static ProviderChargeResult error(String message) {
        return new ProviderChargeResult(Status.ERROR, null, "provider_error", message, null);
    }
}
