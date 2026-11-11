package com.example.omp.order.clients.grpc;

import com.example.omp.order.clients.grpc.dto.ReservationDto;
import com.example.omp.order.clients.grpc.dto.StockLevelDto;
import com.example.omp.order.error.*;
import com.example.omp.proto.inventory.*;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.grpc.StatusRuntimeException;
import java.util.UUID;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * gRPC client wrapper for inventory-service.
 *
 * <p>Hides the gRPC stub, proto types, and status exceptions behind a domain-friendly API. Business
 * code never touches protobuf.
 *
 * <p><b>Interceptors:</b> the injected stub has the following interceptors applied (see {@link
 * com.example.omp.order.config.GrpcClientConfig}):
 *
 * <ul>
 *   <li>Auth forwarding — copies the caller's JWT into gRPC metadata.
 *   <li>Request ID — copies X-Request-Id from MDC into gRPC metadata.
 *   <li>Tracing — provided by grpc-spring-boot-starter; propagates traceparent.
 * </ul>
 *
 * <p><b>Resilience:</b> mutation methods use a Resilience4j circuit breaker and retry policy with
 * an explicit five-second deadline per gRPC attempt. Recovery lookups have a five-second deadline
 * and are retried by the recovery worker.
 */
@Component
public class InventoryClient {

    private static final Logger log = LoggerFactory.getLogger(InventoryClient.class);

    private final InventoryServiceGrpc.InventoryServiceBlockingStub stub;
    private final GrpcExceptionMapper exceptionMapper;

    public InventoryClient(
            @GrpcClient("inventory-service") InventoryServiceGrpc.InventoryServiceBlockingStub stub,
            GrpcExceptionMapper exceptionMapper) {
        this.stub = stub;
        this.exceptionMapper = exceptionMapper;
    }

    // =========================================================================
    // Query
    // =========================================================================

    public ReservationDto get(UUID reservationId) {
        try {
            return toDomain(
                    stub.withDeadlineAfter(5, java.util.concurrent.TimeUnit.SECONDS)
                            .getReservation(
                                    GetReservationRequest.newBuilder()
                                            .setReservationId(reservationId.toString())
                                            .build()));
        } catch (StatusRuntimeException failure) {
            throw exceptionMapper.map(failure, "inventory-service");
        }
    }

    @CircuitBreaker(name = "inventoryClient")
    @Retry(name = "inventoryClient")
    public StockLevelDto getStockLevel(String sku, String warehouseId) {
        try {
            GetStockLevelRequest request =
                    GetStockLevelRequest.newBuilder()
                            .setSku(sku)
                            .setWarehouseId(warehouseId != null ? warehouseId : "")
                            .build();

            StockLevel response = stub.getStockLevel(request);
            return toDomain(response);
        } catch (StatusRuntimeException e) {
            throw exceptionMapper.map(e, "inventory-service");
        }
    }

    // =========================================================================
    // Reserve — idempotent on (orderId, sku)
    // =========================================================================

    /**
     * Reserves stock for a SKU. Idempotent: calling twice with the same (orderId, sku) returns the
     * existing reservation.
     */
    @CircuitBreaker(name = "inventoryClient")
    @Retry(name = "inventoryClient")
    public ReservationDto reserve(UUID orderId, String sku, String warehouseId, int quantity) {
        try {
            ReserveStockRequest request =
                    ReserveStockRequest.newBuilder()
                            .setOrderId(orderId.toString())
                            .setSku(sku)
                            .setWarehouseId(warehouseId != null ? warehouseId : "WH-1")
                            .setQuantity(quantity)
                            .build();

            Reservation response =
                    stub.withDeadlineAfter(5, java.util.concurrent.TimeUnit.SECONDS)
                            .reserveStock(request);
            ReservationDto reservation = verifyReservation(request, response);
            log.debug(
                    "Reserved {}x{} for order {}: reservation={}",
                    quantity,
                    sku,
                    orderId,
                    response.getId());
            return reservation;
        } catch (StatusRuntimeException e) {
            throw exceptionMapper.map(e, "inventory-service");
        }
    }

    // =========================================================================
    // Release / commit — idempotent
    // =========================================================================

    @CircuitBreaker(name = "inventoryClient")
    @Retry(name = "inventoryClient")
    public ReservationDto release(String reservationId) {
        try {
            ReleaseReservationRequest request =
                    ReleaseReservationRequest.newBuilder().setReservationId(reservationId).build();
            return toDomain(
                    stub.withDeadlineAfter(5, java.util.concurrent.TimeUnit.SECONDS)
                            .releaseReservation(request));
        } catch (StatusRuntimeException e) {
            throw exceptionMapper.map(e, "inventory-service");
        }
    }

    @CircuitBreaker(name = "inventoryClient")
    @Retry(name = "inventoryClient")
    public ReservationDto commit(String reservationId) {
        try {
            CommitReservationRequest request =
                    CommitReservationRequest.newBuilder().setReservationId(reservationId).build();
            return toDomain(
                    stub.withDeadlineAfter(5, java.util.concurrent.TimeUnit.SECONDS)
                            .commitReservation(request));
        } catch (StatusRuntimeException e) {
            throw exceptionMapper.map(e, "inventory-service");
        }
    }

    // =========================================================================
    // Conversion — proto -> client-side DTO
    // =========================================================================

    public java.util.List<ReservationDto> findByOrder(UUID orderId) {
        try {
            return stub
                    .withDeadlineAfter(5, java.util.concurrent.TimeUnit.SECONDS)
                    .findReservationsByOrder(
                            FindReservationsByOrderRequest.newBuilder()
                                    .setOrderId(orderId.toString())
                                    .build())
                    .getReservationsList()
                    .stream()
                    .map(this::toDomain)
                    .toList();
        } catch (StatusRuntimeException failure) {
            throw exceptionMapper.map(failure, "inventory-service");
        }
    }

    private ReservationDto verifyReservation(ReserveStockRequest request, Reservation response) {
        if (!request.getOrderId().equals(response.getOrderId())
                || !request.getSku().equals(response.getSku())
                || !request.getWarehouseId().equals(response.getWarehouseId())
                || request.getQuantity() != response.getQuantity()
                || response.getStatus() != ReservationStatus.RESERVATION_STATUS_ACTIVE)
            throw new DownstreamServiceException(
                    "inventory-service", 502, "Reservation response does not match checkout");
        try {
            return toDomain(response);
        } catch (IllegalArgumentException malformedIdentity) {
            throw new DownstreamServiceException(
                    "inventory-service", 502, "Reservation response has an invalid identity");
        }
    }

    public java.util.List<ReservationDto> compensateOrder(UUID orderId) {
        try {
            return stub
                    .withDeadlineAfter(5, java.util.concurrent.TimeUnit.SECONDS)
                    .compensateOrder(
                            CompensateOrderRequest.newBuilder()
                                    .setOrderId(orderId.toString())
                                    .setReason("Order lifecycle compensation")
                                    .build())
                    .getReservationsList()
                    .stream()
                    .map(this::toDomain)
                    .toList();
        } catch (StatusRuntimeException | IllegalArgumentException failure) {
            throw new DownstreamServiceException(
                    "inventory-service", 502, "Inventory compensation unavailable");
        }
    }

    private ReservationDto toDomain(Reservation proto) {
        return new ReservationDto(
                UUID.fromString(proto.getId()),
                proto.getSku(),
                proto.getWarehouseId(),
                proto.getQuantity(),
                UUID.fromString(proto.getOrderId()),
                proto.getStatus().name(),
                proto.getCreatedAtEpochMillis(),
                proto.getUpdatedAtEpochMillis());
    }

    private StockLevelDto toDomain(StockLevel proto) {
        return new StockLevelDto(
                proto.getSku(),
                proto.getWarehouseId(),
                proto.getQuantityOnHand(),
                proto.getQuantityReserved(),
                proto.getQuantityAvailable(),
                proto.getLastUpdatedEpochMillis());
    }
}
