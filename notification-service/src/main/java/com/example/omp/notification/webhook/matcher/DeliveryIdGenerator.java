package com.example.omp.notification.webhook.matcher;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Deterministic delivery IDs.
 *
 * <p>deliveryId = UUIDv5-style hash of (subscriptionId, eventId, schemaVersion). The schema version
 * is a constant ("v1") so that a future change to the delivery envelope semantics can be rolled out
 * with new IDs.
 *
 * <p>Rationale: Kafka is at-least-once. If the matcher processes the same event twice, the same
 * deliveryId is produced, and the delivery worker can skip duplicates that have already succeeded.
 */
@Component
public class DeliveryIdGenerator {

    private static final String SCHEMA_VERSION = "v1";

    public UUID generate(UUID subscriptionId, String eventId) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(SCHEMA_VERSION.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(subscriptionId.toString().getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(eventId.getBytes(StandardCharsets.UTF_8));
            byte[] digest = md.digest();

            // Fold the 32-byte SHA-256 into a 16-byte UUID with version/variant bits set.
            byte[] uuidBytes = new byte[16];
            System.arraycopy(digest, 0, uuidBytes, 0, 16);

            // Set version 5 (name-based, SHA-1 in RFC 4122; we're using SHA-256 here,
            // which is fine for our purposes — this is a deterministic identifier,
            // not a cryptographic UUID).
            uuidBytes[6] = (byte) ((uuidBytes[6] & 0x0f) | 0x50);
            // Set variant to IETF.
            uuidBytes[8] = (byte) ((uuidBytes[8] & 0x3f) | 0x80);

            long msb = 0;
            long lsb = 0;
            for (int i = 0; i < 8; i++) msb = (msb << 8) | (uuidBytes[i] & 0xff);
            for (int i = 8; i < 16; i++) lsb = (lsb << 8) | (uuidBytes[i] & 0xff);
            return new UUID(msb, lsb);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
