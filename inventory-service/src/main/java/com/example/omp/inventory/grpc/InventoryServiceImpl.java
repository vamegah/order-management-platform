package com.example.omp.inventory.grpc;

import com.example.omp.inventory.grpc.handler.BulkOperationsHandler;
import com.example.omp.inventory.grpc.handler.ReservationHandler;
import com.example.omp.inventory.grpc.handler.StockQueryHandler;
import com.example.omp.inventory.grpc.handler.StockUpdateStreamHandler;
import com.example.omp.proto.inventory.BulkReserveItem;
import com.example.omp.proto.inventory.BulkReserveResponse;
import com.example.omp.proto.inventory.CheckAvailabilityRequest;
import com.example.omp.proto.inventory.CheckAvailabilityResponse;
import com.example.omp.proto.inventory.CommitReservationRequest;
import com.example.omp.proto.inventory.GetReservationRequest;
import com.example.omp.proto.inventory.GetStockLevelRequest;
import com.example.omp.proto.inventory.InventoryServiceGrpc;
import com.example.omp.proto.inventory.ReleaseReservationRequest;
import com.example.omp.proto.inventory.Reservation;
import com.example.omp.proto.inventory.ReserveStockRequest;
import com.example.omp.proto.inventory.StockLevel;
import com.example.omp.proto.inventory.StockUpdate;
import com.example.omp.proto.inventory.StreamStockUpdatesRequest;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * gRPC implementation of {@link InventoryServiceGrpc.InventoryServiceImplBase}.
 *
 * <p>This class is the thin entry point for every RPC. It doesn't contain business logic — each
 * method translates the proto request and delegates to a specialized handler.
 *
 * <p><b>Discovery:</b> {@code @GrpcService} is a grpc-spring-boot-starter annotation. The starter
 * scans for classes with this annotation at startup and registers them with the gRPC server.
 * Nothing else is needed — no explicit wiring, no XML.
 *
 * <p><b>Error handling:</b> handlers throw domain exceptions. The {@link
 * com.example.omp.inventory.grpc.advice.GrpcExceptionAdvice} maps them to gRPC status codes. The
 * try/catch blocks in this class catch any exception, including ones not handled by the advice, and
 * route them to {@code observer.onError()} so the client receives a proper error response rather
 * than a hang.
 *
 * <p><b>Why split into handlers?</b> A single class with 7 RPC methods, each with business logic,
 * would exceed 400 lines. The split keeps each handler focused on one concern (queries,
 * reservations, bulk, streaming) and makes each independently testable.
 */
@GrpcService
public class InventoryServiceImpl extends InventoryServiceGrpc.InventoryServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(InventoryServiceImpl.class);

    private final StockQueryHandler stockQueryHandler;
    private final ReservationHandler reservationHandler;
    private final BulkOperationsHandler bulkOperationsHandler;
    private final StockUpdateStreamHandler streamHandler;

    public InventoryServiceImpl(
            StockQueryHandler stockQueryHandler,
            ReservationHandler reservationHandler,
            BulkOperationsHandler bulkOperationsHandler,
            StockUpdateStreamHandler streamHandler) {
        this.stockQueryHandler = stockQueryHandler;
        this.reservationHandler = reservationHandler;
        this.bulkOperationsHandler = bulkOperationsHandler;
        this.streamHandler = streamHandler;
    }

    @Override
    public void findReservationsByOrder(
            com.example.omp.proto.inventory.FindReservationsByOrderRequest request,
            StreamObserver<com.example.omp.proto.inventory.FindReservationsByOrderResponse>
                    observer) {
        try {
            observer.onNext(reservationHandler.findByOrder(request));
            observer.onCompleted();
        } catch (org.springframework.security.access.AccessDeniedException failure) {
            observer.onError(
                    io.grpc.Status.PERMISSION_DENIED
                            .withDescription("Recovery scope required")
                            .asRuntimeException());
        } catch (org.springframework.security.core.AuthenticationException failure) {
            observer.onError(
                    io.grpc.Status.UNAUTHENTICATED
                            .withDescription("Authentication required")
                            .asRuntimeException());
        } catch (IllegalArgumentException failure) {
            observer.onError(
                    io.grpc.Status.INVALID_ARGUMENT
                            .withDescription("Invalid order ID")
                            .asRuntimeException());
        } catch (IllegalStateException failure) {
            observer.onError(
                    io.grpc.Status.FAILED_PRECONDITION
                            .withDescription("Recovery lookup limit exceeded")
                            .asRuntimeException());
        } catch (Exception failure) {
            observer.onError(
                    io.grpc.Status.INTERNAL
                            .withDescription("Recovery lookup failed")
                            .asRuntimeException());
        }
    }

    // =========================================================================
    // Unary — stock queries
    // =========================================================================

    /**
     * Gets the current stock level for a SKU in a warehouse.
     *
     * <p><b>Unary RPC pattern:</b> the client sends one request and expects one response. On
     * success, call {@code onNext(response)} followed by {@code onCompleted()}. On failure, call
     * {@code onError(throwable)}.
     *
     * <p>Calling {@code onCompleted()} after {@code onError()}, or vice versa, throws {@code
     * IllegalStateException}. Only one terminal signal is allowed.
     */
    @Override
    public void getStockLevel(
            GetStockLevelRequest request, StreamObserver<StockLevel> responseObserver) {
        log.debug("GetStockLevel sku={} warehouse={}", request.getSku(), request.getWarehouseId());
        try {
            StockLevel response = stockQueryHandler.getStockLevel(request);
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            // The exception advice converts domain exceptions to proper
            // status codes. This catch-all handles anything unexpected and
            // ensures the client receives a response instead of a hang.
            responseObserver.onError(e);
        }
    }

    /** Checks whether N units are available. Used by order-service before placing an order. */
    @Override
    public void checkAvailability(
            CheckAvailabilityRequest request,
            StreamObserver<CheckAvailabilityResponse> responseObserver) {
        log.debug(
                "CheckAvailability sku={} qty={} warehouse={}",
                request.getSku(),
                request.getRequestedQuantity(),
                request.getWarehouseId());
        try {
            CheckAvailabilityResponse response = stockQueryHandler.checkAvailability(request);
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(e);
        }
    }

    // =========================================================================
    // Unary — reservations
    // =========================================================================

    /**
     * Reserves N units for an order.
     *
     * <p>Idempotent on {@code (orderId, sku)}: calling twice with the same values returns the
     * existing reservation rather than creating a duplicate. This is important because network
     * retries are common in gRPC — a client that times out before receiving the response may retry,
     * and the server must not double-reserve.
     */
    @Override
    public void reserveStock(
            ReserveStockRequest request, StreamObserver<Reservation> responseObserver) {
        log.debug(
                "ReserveStock sku={} qty={} order={}",
                request.getSku(),
                request.getQuantity(),
                request.getOrderId());
        try {
            Reservation response = reservationHandler.reserve(request);
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(e);
        }
    }

    /**
     * Releases a reservation, returning its quantity to available stock. Used when an order is
     * cancelled.
     */
    @Override
    public void releaseReservation(
            ReleaseReservationRequest request, StreamObserver<Reservation> responseObserver) {
        log.debug("ReleaseReservation id={}", request.getReservationId());
        try {
            Reservation response = reservationHandler.release(request);
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(e);
        }
    }

    /**
     * Commits a reservation: converts it to a permanent stock decrement. Used when an order ships.
     */
    @Override
    public void commitReservation(
            CommitReservationRequest request, StreamObserver<Reservation> responseObserver) {
        log.debug("CommitReservation id={}", request.getReservationId());
        try {
            Reservation response = reservationHandler.commit(request);
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(e);
        }
    }

    /** Fetches a reservation by ID. */
    @Override
    public void getReservation(
            GetReservationRequest request, StreamObserver<Reservation> responseObserver) {
        log.debug("GetReservation id={}", request.getReservationId());
        try {
            Reservation response = reservationHandler.get(request);
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(e);
        }
    }

    // =========================================================================
    // Client streaming — bulk operations
    // =========================================================================
    //
    // CLIENT STREAMING PATTERN:
    //
    // The method returns a StreamObserver<Request> to the client. The client
    // sends items by calling onNext() on that observer, one per item. When
    // the client is done sending, it calls onCompleted(). The server then
    // produces a single response on the responseObserver and completes it.
    //
    // The server implements the returned StreamObserver's three callback
    // methods:
    //   - onNext(item): accumulate or process each incoming item
    //   - onError(t):  client cancelled or errored
    //   - onCompleted(): client finished; now produce the response
    //
    // The implementation lives in BulkOperationsHandler to keep this class
    // thin. Delegating client-streaming RPCs is standard: the handler owns
    // the StreamObserver lifecycle, and this class just wires it up.
    // =========================================================================

    @Override
    public StreamObserver<BulkReserveItem> bulkReserve(
            StreamObserver<BulkReserveResponse> responseObserver) {
        log.debug("BulkReserve stream started");
        return bulkOperationsHandler.bulkReserve(responseObserver);
    }

    // =========================================================================
    // Server streaming — stock updates
    // =========================================================================
    //
    // SERVER STREAMING PATTERN:
    //
    // The client sends one request. The server pushes multiple responses by
    // calling onNext() repeatedly, then onCompleted() when done. The client
    // sees an Iterator<Response> that yields items as they arrive.
    //
    // Server streaming RPCs often run for a long time. The handler must
    // respect client cancellation: when the client cancels, the server's
    // ServerCallStreamObserver.isCancelled() flips to true and the handler
    // should stop pushing.
    //
    // The implementation lives in StockUpdateStreamHandler.
    // =========================================================================

    @Override
    public void streamStockUpdates(
            StreamStockUpdatesRequest request, StreamObserver<StockUpdate> responseObserver) {
        log.debug("StreamStockUpdates subscribed to {} SKUs", request.getSkusCount());
        streamHandler.streamUpdates(request, responseObserver);
    }
}
