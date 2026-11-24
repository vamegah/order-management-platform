package com.example.omp.payment.service;

import com.example.omp.payment.domain.Payment;
import com.example.omp.payment.domain.PaymentStatus;
import com.example.omp.payment.metrics.PaymentMetrics;
import com.example.omp.payment.provider.*;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates a payment charge:
 *
 * <p>1. (In a transaction) persist Payment as PENDING; write payment.initiated to the outbox;
 * commit. 2. (Outside the transaction) call the provider. 3. (In a new transaction) update Payment
 * and write payment.confirmed or payment.failed to the outbox; commit.
 *
 * <p>Splitting the transaction boundaries is deliberate: the provider call is a network operation
 * and must not hold a DB transaction open.
 */
@Service
public class PaymentApplicationService {

    private static final Logger log = LoggerFactory.getLogger(PaymentApplicationService.class);

    private final PaymentTransactionalOps transactionalOps;
    private final PaymentProvider provider;
    private final PaymentMetrics metrics;

    public PaymentApplicationService(
            PaymentTransactionalOps transactionalOps,
            PaymentProvider provider,
            PaymentMetrics metrics) {
        this.transactionalOps = transactionalOps;
        this.provider = provider;
        this.metrics = metrics;
    }

    public ChargeOutcome charge(ChargeCommand command) {
        Payment payment = transactionalOps.createOrFindPending(command, provider.providerName());

        return chargePending(payment);
    }

    public ChargeOutcome confirm(UUID paymentId, UUID customerId, String paymentMethodId) {
        return chargePending(
                transactionalOps.bindPaymentMethod(
                        paymentId, customerId, paymentMethodId, provider.providerName()));
    }

    private ChargeOutcome chargePending(Payment payment) {
        if (payment.getStatus() != PaymentStatus.PENDING) {
            return ChargeOutcome.fromExisting(payment);
        }

        ProviderChargeResult result = callProvider(payment);
        Payment applied = transactionalOps.applyChargeResult(payment.getId(), result);
        if (applied.getStatus() != PaymentStatus.PENDING
                && !((result.status() == ProviderChargeResult.Status.SUCCEEDED
                                && applied.getStatus() == PaymentStatus.CONFIRMED)
                        || (result.status() == ProviderChargeResult.Status.DECLINED
                                && applied.getStatus() == PaymentStatus.FAILED)))
            return ChargeOutcome.fromExisting(applied);

        return switch (result.status()) {
            case SUCCEEDED -> ChargeOutcome.confirmed(applied);
            case DECLINED -> ChargeOutcome.declined(applied);
            case REQUIRES_ACTION ->
                    ChargeOutcome.requiresAction(
                            applied, result.requiresActionUrl(), result.clientSecret());
            case PENDING -> ChargeOutcome.pending(applied);
            case ERROR -> ChargeOutcome.error(applied, "Provider outcome pending reconciliation");
        };
    }

    private ProviderChargeResult callProvider(Payment payment) {
        ChargeRequest request =
                new ChargeRequest(
                        payment.getId(),
                        payment.getOrderId(),
                        payment.getCustomerId(),
                        payment.getAmountMinor(),
                        payment.getCurrency(),
                        payment.getIdempotencyKey(),
                        "Order " + payment.getOrderId(),
                        payment.getPaymentMethodId(),
                        payment.getProviderReference());

        Instant start = Instant.now();
        try {
            ProviderChargeResult result = provider.charge(request);
            metrics.recordCharge(provider.providerName(), classify(result));
            metrics.recordChargeDuration(
                    provider.providerName(), Duration.between(start, Instant.now()));
            return result;
        } catch (RuntimeException e) {
            metrics.recordCharge(provider.providerName(), "error");
            metrics.recordChargeDuration(
                    provider.providerName(), Duration.between(start, Instant.now()));
            log.warn("Provider outcome unresolved for payment {}", payment.getId());
            return ProviderChargeResult.error("Provider outcome unresolved");
        }
    }

    private static String classify(ProviderChargeResult r) {
        return switch (r.status()) {
            case SUCCEEDED -> "success";
            case DECLINED -> "declined";
            case REQUIRES_ACTION -> "requires_action";
            case PENDING -> "pending";
            case ERROR -> "error";
        };
    }

    @Transactional(readOnly = true)
    public Payment get(UUID paymentId) {
        return transactionalOps.findById(paymentId);
    }

    public record ChargeCommand(
            UUID orderId,
            UUID customerId,
            long amountMinor,
            String currency,
            String idempotencyKey,
            String paymentMethodId) {
        public ChargeCommand(
                UUID orderId,
                UUID customerId,
                long amountMinor,
                String currency,
                String idempotencyKey) {
            this(orderId, customerId, amountMinor, currency, idempotencyKey, null);
        }
    }

    public record ChargeOutcome(
            String result,
            UUID paymentId,
            String providerReference,
            String failureCode,
            String failureMessage,
            String requiresActionUrl,
            String clientSecret) {
        public ChargeOutcome(
                String result,
                UUID paymentId,
                String providerReference,
                String failureCode,
                String failureMessage,
                String requiresActionUrl) {
            this(
                    result,
                    paymentId,
                    providerReference,
                    failureCode,
                    failureMessage,
                    requiresActionUrl,
                    null);
        }

        @Override
        public String toString() {
            return "ChargeOutcome[result=" + result + ", paymentId=" + paymentId + "]";
        }

        public static ChargeOutcome pending(Payment p) {
            return new ChargeOutcome(
                    "pending", p.getId(), p.getProviderReference(), null, null, null);
        }

        public static ChargeOutcome confirmed(Payment p) {
            return new ChargeOutcome(
                    "confirmed", p.getId(), p.getProviderReference(), null, null, null);
        }

        public static ChargeOutcome declined(Payment p) {
            return new ChargeOutcome(
                    "declined", p.getId(), null, p.getFailureCode(), p.getFailureMessage(), null);
        }

        public static ChargeOutcome requiresAction(Payment p, String url) {
            return requiresAction(p, url, null);
        }

        public static ChargeOutcome requiresAction(Payment p, String url, String secret) {
            return new ChargeOutcome(
                    "requires_action",
                    p.getId(),
                    p.getProviderReference(),
                    null,
                    null,
                    url,
                    secret);
        }

        public static ChargeOutcome error(Payment p, String message) {
            return new ChargeOutcome("error", p.getId(), null, "provider_error", message, null);
        }

        public static ChargeOutcome fromExisting(Payment p) {
            return new ChargeOutcome(
                    p.getStatus().name().toLowerCase(),
                    p.getId(),
                    p.getProviderReference(),
                    p.getFailureCode(),
                    p.getFailureMessage(),
                    null);
        }
    }

    public static class PaymentNotFoundException extends RuntimeException {
        public PaymentNotFoundException(UUID id) {
            super("Payment not found: " + id);
        }
    }
}
