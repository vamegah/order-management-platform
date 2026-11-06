package com.example.omp.notification.webhook.config;

public final class WebhookKafkaTopics {

    private WebhookKafkaTopics() {}

    public static final String DELIVERIES = "webhooks.outbound.deliveries";

    public static final String RETRY_1 = "webhooks.outbound.deliveries.retry-1";
    public static final String RETRY_2 = "webhooks.outbound.deliveries.retry-2";
    public static final String RETRY_3 = "webhooks.outbound.deliveries.retry-3";
    public static final String RETRY_4 = "webhooks.outbound.deliveries.retry-4";
    public static final String RETRY_5 = "webhooks.outbound.deliveries.retry-5";

    public static final String DLQ = "webhooks.outbound.deliveries.dlq";

    /**
     * Retry topic for a given attempt number (the attempt that is about to be made). attemptNumber
     * == 2 -> RETRY_1, attemptNumber == 3 -> RETRY_2, etc.
     */
    public static String retryTopicForAttempt(int nextAttemptNumber) {
        return switch (nextAttemptNumber) {
            case 2 -> RETRY_1;
            case 3 -> RETRY_2;
            case 4 -> RETRY_3;
            case 5 -> RETRY_4;
            case 6 -> RETRY_5;
            default ->
                    throw new IllegalArgumentException(
                            "No retry topic for attempt " + nextAttemptNumber);
        };
    }

    public static final String[] ALL_RETRY_TOPICS = {RETRY_1, RETRY_2, RETRY_3, RETRY_4, RETRY_5};
}
