package com.example.omp.payment;

import static org.assertj.core.api.Assertions.*;

import com.example.omp.payment.domain.*;
import com.example.omp.shared.domain.Money;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentLifecycleTest {
    private Payment payment() {
        return new Payment(UUID.randomUUID(), UUID.randomUUID(), Money.usd("10"), "key");
    }

    @Test
    void multiplePartialRefundsConvergeAndCannotExceedBalance() {
        var payment = payment();
        payment.confirm("provider-1");
        payment.applyRefund(Money.usd("4"));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThatThrownBy(() -> payment.applyRefund(Money.usd("7")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(payment.getRemainingRefundable().getAmount()).isEqualByComparingTo("6");
        payment.applyRefund(Money.usd("6"));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThatThrownBy(() -> payment.markFailed("late failure"))
                .isInstanceOf(com.example.omp.payment.error.PaymentStateException.class);
    }

    @Test
    void cancelledPaymentsCannotBecomeFailedOrConfirmed() {
        var payment = payment();
        payment.cancel("cancelled");
        assertThatThrownBy(() -> payment.markFailed("late failure"))
                .isInstanceOf(com.example.omp.payment.error.PaymentStateException.class);
        assertThatThrownBy(() -> payment.confirm("late success"))
                .isInstanceOf(com.example.omp.payment.error.PaymentStateException.class);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
    }

    @Test
    void zeroRefundDoesNotChangeConfirmedPayment() {
        var payment = payment();
        payment.confirm("provider-1");
        assertThatThrownBy(() -> payment.applyRefund(Money.usd("0")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CONFIRMED);
    }
}
