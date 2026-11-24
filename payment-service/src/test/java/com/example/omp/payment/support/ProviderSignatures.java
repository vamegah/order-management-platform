package com.example.omp.payment.support;

public final class ProviderSignatures {
    private ProviderSignatures() {}

    public static String noop(String body) {
        return sign(body, "noop-dev-secret", java.time.Instant.now().getEpochSecond());
    }

    public static String sign(String body, String secret, long timestamp) {
        try {
            var mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(
                    new javax.crypto.spec.SecretKeySpec(
                            secret.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            "HmacSHA256"));
            return "t="
                    + timestamp
                    + ",v1="
                    + java.util.HexFormat.of()
                            .formatHex(
                                    mac.doFinal(
                                            (timestamp + "." + body)
                                                    .getBytes(
                                                            java.nio.charset.StandardCharsets
                                                                    .UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
