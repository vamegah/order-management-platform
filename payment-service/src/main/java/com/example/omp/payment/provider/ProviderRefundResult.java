package com.example.omp.payment.provider;

public record ProviderRefundResult(Status status, String providerReference, String errorMessage) {

    public enum Status {
        PENDING,
        SUCCEEDED,
        FAILED,
        ERROR
    }

    public static ProviderRefundResult pending(String ref) {
        return new ProviderRefundResult(Status.PENDING, ref, null);
    }

    public static ProviderRefundResult succeeded(String ref) {
        return new ProviderRefundResult(Status.SUCCEEDED, ref, null);
    }

    public static ProviderRefundResult failed(String message) {
        return new ProviderRefundResult(Status.FAILED, null, message);
    }

    public static ProviderRefundResult failed(String reference, String message) {
        return new ProviderRefundResult(Status.FAILED, reference, message);
    }

    public static ProviderRefundResult error(String message) {
        return new ProviderRefundResult(Status.ERROR, null, message);
    }
}
