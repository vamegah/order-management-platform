package com.example.omp.inventory.grpc.handler;

import com.example.omp.inventory.domain.StockLevel;
import com.example.omp.inventory.repository.StockLevelRepository;
import com.example.omp.proto.inventory.StockUpdate;
import com.example.omp.proto.inventory.StockUpdateReason;
import com.example.omp.proto.inventory.StreamStockUpdatesRequest;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Handles the server-streaming {@code StreamStockUpdates} RPC.
 *
 * <p><b>Server-streaming pattern:</b> the client sends one request; the server pushes many
 * responses. The server keeps the stream open until:
 *
 * <ul>
 *   <li>The client cancels the call (detected via {@link ServerCallStreamObserver#isCancelled()}).
 *   <li>The server decides to close it (e.g., a max-duration limit).
 *   <li>An error occurs.
 * </ul>
 *
 * <p><b>Subscription model:</b> this handler keeps an in-memory registry of active subscribers.
 * Each subscriber has a set of subscribed SKUs and a reference to its response observer. When stock
 * changes (via a {@link StockLevel} update), a notification is dispatched to all subscribers whose
 * filter matches.
 *
 * <p><b>Notification source:</b> in Phase 3, the only producer of stock updates is the reservation
 * handler itself. When a reservation is created or released, the handler calls {@link
 * #publishUpdate(StockLevel, StockUpdateReason)}, which fans out to subscribers.
 *
 * <p><b>Backpressure:</b> if a subscriber is slow, the response observer's queue grows. {@link
 * ServerCallStreamObserver#isReady()} tells us whether the subscriber can accept more items. If not
 * ready, we skip the update for that subscriber (dropping intermediate states is acceptable for
 * stock updates — the client will see the next one).
 */
@Component
public class StockUpdateStreamHandler {

    private static final Logger log = LoggerFactory.getLogger(StockUpdateStreamHandler.class);

    private final StockLevelRepository stockLevelRepository;

    /**
     * Active subscribers, keyed by a subscription ID. Using a ConcurrentMap because subscriptions
     * are added and removed from different threads (the gRPC server's thread pool handles each RPC
     * on its own thread).
     */
    private final ConcurrentMap<Long, Subscription> subscribers = new ConcurrentHashMap<>();

    private final java.util.concurrent.atomic.AtomicLong subscriptionIdGen =
            new java.util.concurrent.atomic.AtomicLong();

    public StockUpdateStreamHandler(StockLevelRepository stockLevelRepository) {
        this.stockLevelRepository = stockLevelRepository;
    }

    // =========================================================================
    // Subscription handling
    // =========================================================================

    /**
     * Entry point for the streaming RPC. The gRPC framework calls this when a client opens the
     * stream.
     */
    public void streamUpdates(
            StreamStockUpdatesRequest request, StreamObserver<StockUpdate> responseObserver) {

        // gRPC provides a richer observer interface for server-streaming
        // calls. It exposes cancellation and readiness signals we use to
        // manage the subscription.
        if (!(responseObserver instanceof ServerCallStreamObserver<StockUpdate> serverObserver)) {
            // Should never happen: gRPC always passes a ServerCallStreamObserver.
            // Defensive check.
            throw new IllegalStateException(
                    "Expected ServerCallStreamObserver but got " + responseObserver.getClass());
        }

        long subscriptionId = subscriptionIdGen.incrementAndGet();
        Subscription subscription =
                new Subscription(
                        subscriptionId,
                        Set.copyOf(request.getSkusList()),
                        request.getWarehouseId(),
                        serverObserver);

        subscribers.put(subscriptionId, subscription);
        log.info(
                "Client subscribed: id={} skus={} warehouse={}",
                subscriptionId,
                subscription.skus,
                subscription.warehouseId);

        // Register the cancellation callback. gRPC calls this when the
        // client cancels the stream, or when the connection drops.
        serverObserver.setOnCancelHandler(
                () -> {
                    subscribers.remove(subscriptionId);
                    log.info("Client cancelled subscription: id={}", subscriptionId);
                });

        // Optionally send an initial snapshot for each subscribed SKU.
        // This is a common pattern: the client doesn't wait for the first
        // change to see the current state.
        sendInitialSnapshot(subscription);

        // Do NOT call onCompleted() here. The stream stays open until the
        // client cancels or the server closes it.
    }

    // =========================================================================
    // Fan-out
    // =========================================================================

    /**
     * Publishes a stock update to all matching subscribers. Called by the reservation handler when
     * stock changes.
     */
    public void publishUpdate(StockLevel stock, StockUpdateReason reason) {
        StockUpdate update = buildUpdate(stock, reason);

        int delivered = 0;
        for (Subscription sub : subscribers.values()) {
            if (sub.matches(stock)) {
                try {
                    // Only send if the subscriber can accept more items.
                    // Skipping is acceptable: the next update will
                    // supersede it.
                    if (sub.observer.isReady()) {
                        sub.observer.onNext(update);
                        delivered++;
                    }
                } catch (Exception e) {
                    // If sending fails, the subscriber is gone; remove it.
                    // gRPC will typically also fire the cancellation callback.
                    log.warn(
                            "Failed to publish update to subscription {}: {}",
                            sub.id,
                            e.getMessage());
                    subscribers.remove(sub.id);
                }
            }
        }

        if (delivered > 0) {
            log.debug(
                    "Published stock update for sku={} to {} subscribers",
                    stock.getSku(),
                    delivered);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void sendInitialSnapshot(Subscription sub) {
        for (String sku : sub.skus) {
            var stocks =
                    sub.warehouseId.isEmpty()
                            ? stockLevelRepository.findBySku(sku)
                            : stockLevelRepository
                                    .findBySkuAndWarehouseId(sku, sub.warehouseId)
                                    .map(java.util.List::of)
                                    .orElse(java.util.List.of());
            for (StockLevel stock : stocks) {
                if (sub.observer.isReady()) {
                    sub.observer.onNext(
                            buildUpdate(stock, StockUpdateReason.STOCK_UPDATE_REASON_ADJUSTMENT));
                }
            }
        }
    }

    private StockUpdate buildUpdate(StockLevel stock, StockUpdateReason reason) {
        return StockUpdate.newBuilder()
                .setSku(stock.getSku())
                .setWarehouseId(stock.getWarehouseId())
                .setQuantityOnHand(stock.getQuantityOnHand())
                .setQuantityReserved(stock.getQuantityReserved())
                .setQuantityAvailable(stock.getQuantityAvailable())
                .setReason(reason)
                .setOccurredAtEpochMillis(Instant.now().toEpochMilli())
                .build();
    }

    /** A single subscriber's state. */
    private static final class Subscription {
        final long id;
        final Set<String> skus;
        final String warehouseId; // empty = all warehouses
        final ServerCallStreamObserver<StockUpdate> observer;

        Subscription(
                long id,
                Set<String> skus,
                String warehouseId,
                ServerCallStreamObserver<StockUpdate> observer) {
            this.id = id;
            this.skus = skus;
            this.warehouseId = warehouseId;
            this.observer = observer;
        }

        boolean matches(StockLevel stock) {
            boolean skuMatch = skus.isEmpty() || skus.contains(stock.getSku());
            boolean whMatch = warehouseId.isEmpty() || warehouseId.equals(stock.getWarehouseId());
            return skuMatch && whMatch;
        }
    }
}
