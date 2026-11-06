package com.example.omp.inventory.grpc.handler;

import com.example.omp.inventory.domain.StockLevel;
import com.example.omp.inventory.error.InventoryNotFoundException;
import com.example.omp.inventory.grpc.converter.ProtoConverters;
import com.example.omp.inventory.repository.StockLevelRepository;
import com.example.omp.proto.inventory.CheckAvailabilityRequest;
import com.example.omp.proto.inventory.CheckAvailabilityResponse;
import com.example.omp.proto.inventory.GetStockLevelRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles unary stock-query RPCs.
 *
 * <p>Read-only operations. Marked {@code @Transactional(readOnly = true)} so Hibernate skips dirty
 * checking and can route to a read replica in the future (Phase 7).
 */
@Component
public class StockQueryHandler {

    private final StockLevelRepository stockLevelRepository;

    public StockQueryHandler(StockLevelRepository stockLevelRepository) {
        this.stockLevelRepository = stockLevelRepository;
    }

    /**
     * Returns the stock level for one SKU in one warehouse.
     *
     * @throws InventoryNotFoundException if no stock record exists
     */
    @Transactional(readOnly = true)
    public com.example.omp.proto.inventory.StockLevel getStockLevel(GetStockLevelRequest request) {

        StockLevel stock =
                stockLevelRepository
                        .findBySkuAndWarehouseId(request.getSku(), request.getWarehouseId())
                        .orElseThrow(
                                () ->
                                        new InventoryNotFoundException(
                                                "No stock record for SKU '"
                                                        + request.getSku()
                                                        + "' in warehouse '"
                                                        + request.getWarehouseId()
                                                        + "'"));

        return ProtoConverters.toProto(stock);
    }

    /**
     * Checks whether a quantity is available. Returns a response with available=true/false; doesn't
     * throw for unavailability (the caller may want to reduce quantity rather than fail).
     *
     * @throws InventoryNotFoundException if no stock record exists at all — distinct from "stock
     *     exists but insufficient"
     */
    @Transactional(readOnly = true)
    public CheckAvailabilityResponse checkAvailability(CheckAvailabilityRequest request) {

        StockLevel stock =
                stockLevelRepository
                        .findBySkuAndWarehouseId(request.getSku(), request.getWarehouseId())
                        .orElseThrow(
                                () ->
                                        new InventoryNotFoundException(
                                                "No stock record for SKU '"
                                                        + request.getSku()
                                                        + "' in warehouse '"
                                                        + request.getWarehouseId()
                                                        + "'"));

        int available = stock.getQuantityAvailable();
        int requested = request.getRequestedQuantity();

        return CheckAvailabilityResponse.newBuilder()
                .setAvailable(available >= requested)
                .setAvailableQuantity(available)
                .build();
    }
}
