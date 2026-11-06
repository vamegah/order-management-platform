package com.example.omp.notification.support;

import java.util.UUID;

/** Fixed test tokens matching the mock JWT decoder in {@code TestSecurityConfig}. */
public final class TestTokens {

    public static final UUID ALICE_CUSTOMER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000101");
    public static final UUID BOB_CUSTOMER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000102");
    public static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000199");

    private TestTokens() {}

    public static String alice() {
        return "test-token-alice";
    }

    public static String admin() {
        return "test-token-admin";
    }

    public static String bob() {
        return "test-token-bob";
    }
}
