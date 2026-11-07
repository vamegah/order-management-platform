package com.example.omp.order.service;

import com.example.omp.order.clients.grpc.InventoryClient;
import com.example.omp.order.clients.grpc.PaymentClient;
import com.example.omp.order.clients.grpc.dto.PaymentIntentDto;
import com.example.omp.order.clients.grpc.dto.ReservationDto;
import com.example.omp.order.clients.grpc.interceptor.ServiceTokenScope;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "omp.order.recovery.enabled", havingValue = "true")
public class CheckoutRecoveryJob {
    private static final int MAX_DISCOVERED_RESOURCES = 100;
    private final CheckoutJournal journal;
    private final OrderCleanupQueue cleanup;
    private final InventoryClient inventory;
    private final PaymentClient payments;
    private final MeterRegistry metrics;
    private final Path tokenFile;

    public CheckoutRecoveryJob(
            CheckoutJournal journal,
            OrderCleanupQueue cleanup,
            InventoryClient inventory,
            PaymentClient payments,
            MeterRegistry metrics,
            @Value("${omp.order.recovery.token-file}") Path tokenFile) {
        this.journal = journal;
        this.cleanup = cleanup;
        this.inventory = inventory;
        this.payments = payments;
        this.metrics = metrics;
        this.tokenFile = tokenFile;
    }

    @Scheduled(fixedDelayString = "${omp.order.recovery.interval-ms:30000}")
    public void run() {
        try (var identity = ServiceTokenScope.open(readToken())) {
            for (var attempt : journal.claim(10)) {
                boolean success = false;
                try {
                    for (var reservationId :
                            validatedResourceIds(
                                    attempt.orderId(),
                                    inventory.findByOrder(attempt.orderId()),
                                    ReservationDto::id,
                                    ReservationDto::orderId)) {
                        cleanup.enqueue(attempt.orderId(), "RESERVATION", reservationId);
                    }
                    for (var paymentId :
                            validatedResourceIds(
                                    attempt.orderId(),
                                    payments.findByOrder(attempt.orderId()),
                                    PaymentIntentDto::id,
                                    PaymentIntentDto::orderId)) {
                        cleanup.enqueue(attempt.orderId(), "PAYMENT", paymentId);
                    }
                    success = true;
                    metrics.counter("order.recovery.scans").increment();
                } catch (RuntimeException failure) {
                    metrics.counter("order.recovery.failures").increment();
                    LoggerFactory.getLogger(getClass())
                            .warn("Checkout recovery lookup pending: order={}", attempt.orderId());
                } finally {
                    journal.finish(attempt, success);
                }
            }
            cleanup.retryDue();
        } catch (RuntimeException | IOException failure) {
            metrics.counter("order.recovery.worker.failures").increment();
            LoggerFactory.getLogger(getClass())
                    .warn(
                            "Checkout recovery worker will retry: {}",
                            failure.getClass().getSimpleName());
        }
    }

    private String readToken() throws IOException {
        return com.example.omp.order.clients.grpc.interceptor.ServiceTokenFile.read(tokenFile);
    }

    /** Validate the complete lookup result before adding any of that result's cleanup tasks. */
    private static <T> List<UUID> validatedResourceIds(
            UUID orderId, List<T> resources, Function<T, UUID> identity, Function<T, UUID> owner) {
        if (resources == null || resources.size() > MAX_DISCOVERED_RESOURCES)
            throw new IllegalStateException("Invalid checkout recovery discovery response");
        var ids = new ArrayList<UUID>(resources.size());
        var seen = new HashSet<UUID>();
        for (var resource : resources) {
            if (resource == null)
                throw new IllegalStateException("Invalid checkout recovery discovery response");
            UUID id = identity.apply(resource);
            if (id == null || !orderId.equals(owner.apply(resource)) || !seen.add(id))
                throw new IllegalStateException("Invalid checkout recovery discovery response");
            ids.add(id);
        }
        return ids;
    }
}
