package com.example.omp.inventory.repository;

import com.example.omp.inventory.domain.StockLevel;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Repository for {@link StockLevel}. */
@Repository
public interface StockLevelRepository extends JpaRepository<StockLevel, UUID> {

    /** Finds the stock level for one SKU in one warehouse. */
    Optional<StockLevel> findBySkuAndWarehouseId(String sku, String warehouseId);

    /** Finds all stock levels for a SKU across warehouses. */
    List<StockLevel> findBySku(String sku);

    /** Finds stock levels for multiple SKUs. Used by bulk operations. */
    List<StockLevel> findBySkuIn(List<String> skus);

    /**
     * Sums available quantity across all warehouses for a SKU. Useful for "is this SKU in stock
     * anywhere?"
     */
    @Query(
            """
        SELECT COALESCE(SUM(s.quantityOnHand - s.quantityReserved), 0)
        FROM StockLevel s
        WHERE s.sku = :sku
        """)
    int sumAvailableAcrossWarehouses(@Param("sku") String sku);
}
