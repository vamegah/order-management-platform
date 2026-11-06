package com.example.omp.notification.webhook.matcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.example.omp.notification.webhook.WebhookSubscription;
import com.example.omp.notification.webhook.WebhookSubscriptionRepository;
import com.example.omp.notification.webhook.metrics.WebhookMetrics;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

class WebhookEventTypesTest {
    @ParameterizedTest
    @CsvSource({
        "ORDER_PLACED,order.placed",
        "ORDER_SHIPPED,order.shipped",
        "ORDER_CANCELLED,order.cancelled",
        "RESERVATION_CREATED,inventory.reserved",
        "RESERVATION_RELEASED,inventory.released",
        "PAYMENT_INTENT_CREATED,payment.initiated",
        "PAYMENT_CONFIRMED,payment.confirmed",
        "PAYMENT_FAILED,payment.failed",
        "PAYMENT_REFUNDED,payment.refunded"
    })
    void publishedAliasesMatchInBothDirections(String canonical, String alias) {
        assertThat(WebhookEventTypes.matchingTypes(canonical)).containsExactly(canonical, alias);
        assertThat(WebhookEventTypes.matchingTypes(alias)).containsExactly(alias, canonical);
    }

    @Test
    void unrelatedTypesAreNotRewritten() {
        assertThat(WebhookEventTypes.matchingTypes("PAYMENT_PARTIALLY_REFUNDED"))
                .containsExactly("PAYMENT_PARTIALLY_REFUNDED");
    }

    @Test
    void matcherUsesTheSubscribedAliasAndDeduplicatesSubscriptionsListingBoth() {
        var repository = mock(WebhookSubscriptionRepository.class);
        var publisher = mock(WebhookDeliveryPublisher.class);
        var ids = new DeliveryIdGenerator();
        var matcher =
                new SubscriptionMatcher(repository, publisher, ids, mock(WebhookMetrics.class));
        var both = new WebhookSubscription();
        both.setId(UUID.randomUUID());
        var dotted = new WebhookSubscription();
        dotted.setId(UUID.randomUUID());
        when(repository.findEnabledByEventType("PAYMENT_REFUNDED")).thenReturn(List.of(both));
        when(repository.findEnabledByEventType("payment.refunded"))
                .thenReturn(List.of(both, dotted));
        matcher.matchAndPublish("event", "PAYMENT_REFUNDED", null);
        var messages = ArgumentCaptor.forClass(DeliveryMessage.class);
        verify(publisher, times(2)).publish(messages.capture());
        assertThat(messages.getAllValues())
                .extracting(DeliveryMessage::subscriptionId)
                .containsExactly(both.getId(), dotted.getId());
        assertThat(messages.getAllValues())
                .extracting(DeliveryMessage::eventType)
                .containsExactly("PAYMENT_REFUNDED", "payment.refunded");
        assertThat(messages.getAllValues().get(1).deliveryId())
                .isEqualTo(ids.generate(dotted.getId(), "event"));
    }
}
