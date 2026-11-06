package com.example.omp.notification.webhook;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

/**
 * Generates subscriber HMAC secrets.
 *
 * <p>Format: 32 random bytes, base64url-encoded without padding (43 chars). Length is comfortably
 * above the DB's CHECK constraint (>= 32).
 */
@Component
public class SecretGenerator {

    private static final int SECRET_BYTES = 32;
    private final SecureRandom random = new SecureRandom();

    public String generate() {
        byte[] bytes = new byte[SECRET_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
