package com.example.omp.payment;

import static org.assertj.core.api.Assertions.*;

import com.example.omp.payment.domain.Payment;
import com.example.omp.shared.domain.Money;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PaymentFulfillmentFenceTest {
    private Payment confirmed() {
        var payment = new Payment(UUID.randomUUID(), UUID.randomUUID(), Money.usd("10"));
        payment.confirm("provider-original");
        return payment;
    }

    @Test
    void aShipmentDecisionSurvivesRetriesAndPreventsRefundAdmission() {
        var payment = confirmed();
        payment.requireRefundAllowed();
        payment.decideFulfillment("BEGIN");
        assertThatThrownBy(payment::requireRefundAllowed).isInstanceOf(IllegalStateException.class);
        payment.decideFulfillment("BEGIN");
        payment.decideFulfillment("SHIP");
        payment.decideFulfillment("SHIP");
        payment.decideFulfillment("CANCEL");
        payment.decideFulfillment("BEGIN");
        assertThat(payment.getFulfillmentState()).isEqualTo("SHIPPED");
        assertThatThrownBy(payment::requireRefundAllowed).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> payment.decideFulfillment("ALLOW_REFUND"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(payment.getProviderReference()).isEqualTo("provider-original");
    }

    @Test
    void cancellationFencesLateShipmentUntilCompensationAllowsRefunds() {
        var payment = confirmed();
        payment.decideFulfillment("BEGIN");
        payment.decideFulfillment("CANCEL");
        payment.decideFulfillment("CANCEL");
        assertThat(payment.getFulfillmentState()).isEqualTo("CANCELLING");
        assertThatThrownBy(() -> payment.decideFulfillment("SHIP"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> payment.decideFulfillment("BEGIN"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(payment::requireRefundAllowed).isInstanceOf(IllegalStateException.class);
        payment.decideFulfillment("ALLOW_REFUND");
        payment.decideFulfillment("ALLOW_REFUND");
        payment.decideFulfillment("CANCEL");
        payment.requireRefundAllowed();
        payment.applyRefund(Money.usd("10"));
        assertThat(payment.getFulfillmentState()).isEqualTo("REFUNDABLE");
        assertThat(payment.getRemainingRefundable()).isEqualTo(Money.usd("0"));
    }

    @Test
    void pendingAndRefundedPaymentsCannotAuthorizeShipment() {
        var pending = new Payment(UUID.randomUUID(), UUID.randomUUID(), Money.usd("10"));
        assertThatThrownBy(() -> pending.decideFulfillment("BEGIN"))
                .isInstanceOf(IllegalStateException.class);
        var refunded = confirmed();
        refunded.applyRefund(Money.usd("1"));
        assertThatThrownBy(() -> refunded.decideFulfillment("BEGIN"))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SHIP", "ALLOW_REFUND", "", "UNKNOWN"})
    void decisionsCannotSkipTheirPreconditions(String action) {
        var payment = confirmed();
        assertThatThrownBy(() -> payment.decideFulfillment(action))
                .isInstanceOf(RuntimeException.class);
        assertThat(payment.getFulfillmentState()).isEqualTo("OPEN");
    }
}
