package com.example.omp.inventory.service;

import com.example.omp.inventory.domain.Reservation;
import com.example.omp.inventory.events.InventoryEventPublisher;
import com.example.omp.inventory.repository.ReservationRepository;
import com.example.omp.inventory.repository.StockLevelRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Bounded, replica-safe expiry. Each batch commits stock, reservations and events together. */
@Component
@ConditionalOnProperty(name = "omp.inventory.expiry.enabled", havingValue = "true")
public class ReservationExpiryJob {
    private final ReservationRepository reservations;
    private final StockLevelRepository stocks;
    private final InventoryEventPublisher events;
    private final TransactionTemplate transactions;
    private final MeterRegistry metrics;
    private final Duration ttl;
    private final int batchSize;

    public ReservationExpiryJob(
            ReservationRepository reservations,
            StockLevelRepository stocks,
            InventoryEventPublisher events,
            TransactionTemplate transactions,
            MeterRegistry metrics,
            @Value("${omp.inventory.expiry.ttl:PT30M}") Duration ttl,
            @Value("${omp.inventory.expiry.batch-size:100}") int batchSize) {
        if (ttl.isNegative() || ttl.isZero() || batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException(
                    "Expiry requires positive TTL and batch size 1..1000");
        }
        this.reservations = reservations;
        this.stocks = stocks;
        this.events = events;
        this.transactions = transactions;
        this.metrics = metrics;
        this.ttl = ttl;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${omp.inventory.expiry.interval-ms:60000}")
    public void run() {
        try {
            Integer expired =
                    transactions.execute(
                            status -> {
                                var batch =
                                        reservations.lockExpiredBatch(
                                                Instant.now().minus(ttl), batchSize);
                                for (Reservation reservation : batch) {
                                    var stock =
                                            stocks.findBySkuAndWarehouseId(
                                                            reservation.getSku(),
                                                            reservation.getWarehouseId())
                                                    .orElseThrow(
                                                            () ->
                                                                    new IllegalStateException(
                                                                            "Expiry stock record missing"));
                                    stock.release(reservation.getQuantity());
                                    reservation.expire();
                                    events.publishReservationExpired(
                                            reservation, "Reservation TTL exceeded");
                                }
                                return batch.size();
                            });
            metrics.counter("inventory.reservation.expiry.completed")
                    .increment(expired == null ? 0 : expired);
        } catch (RuntimeException failure) {
            metrics.counter("inventory.reservation.expiry.failures").increment();
            LoggerFactory.getLogger(getClass())
                    .warn("Reservation expiry batch rolled back; retry on next tick", failure);
        }
    }
}
