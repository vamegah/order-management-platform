package com.example.omp.product.web;

import com.example.omp.product.dto.CreateProductRequest;
import com.example.omp.product.dto.ProductDto;
import com.example.omp.product.dto.ProductFilter;
import com.example.omp.product.dto.UpdateProductRequest;
import com.example.omp.product.service.ProductService;
import com.example.omp.shared.web.PageResponse;
import com.example.omp.shared.web.pagination.PageableResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * REST endpoints for the product catalog.
 *
 * <p>All endpoints are under {@code /api/v1/products}. The {@code v1} segment is intentional:
 * breaking changes go in {@code v2} rather than mutating {@code v1}.
 *
 * <p>Authentication is required for all endpoints. Write operations require the {@code admin} role
 * (enforced by {@link com.example.omp.product.config.SecurityConfig} and, redundantly, by
 * {@code @PreAuthorize} here).
 */
@RestController
@RequestMapping("/api/v1/products")
@Tag(name = "Products", description = "Product catalog operations")
@SecurityRequirement(name = "bearerAuth")
public class ProductController {

    private final ProductService productService;
    private final PageableResolver pageableResolver;

    public ProductController(ProductService productService, PageableResolver pageableResolver) {
        this.productService = productService;
        this.pageableResolver = pageableResolver;
    }

    // -------------------------------------------------------------------------
    // List & search
    // -------------------------------------------------------------------------

    @GetMapping("/batch")
    @Operation(
            summary =
                    "Read 1 to 100 products in request order; missing IDs and duplicates are omitted")
    public List<ProductDto> batch(@RequestParam List<UUID> ids) {
        return productService.getBatch(ids);
    }

    @GetMapping
    @Operation(
            summary = "List products",
            description =
                    "Paginated listing with optional filters. Returns only ACTIVE products unless a status filter is provided.")
    public PageResponse<ProductDto> list(
            @Parameter(description = "Filter by category slug") @RequestParam(required = false)
                    String categorySlug,
            @Parameter(description = "Filter by category ID") @RequestParam(required = false)
                    UUID categoryId,
            @Parameter(description = "Filter by status (ACTIVE, INACTIVE, DISCONTINUED)")
                    @RequestParam(required = false)
                    String status,
            @Parameter(description = "Case-insensitive substring match on name")
                    @RequestParam(required = false)
                    String nameQuery,
            @Parameter(description = "Page number (0-based)") @RequestParam(required = false)
                    Integer page,
            @Parameter(description = "Page size (1-100)") @RequestParam(required = false)
                    Integer size,
            @Parameter(description = "Sort field and direction, e.g. 'name,asc'")
                    @RequestParam(required = false)
                    String sort) {
        ProductFilter filter = new ProductFilter(categorySlug, categoryId, status, nameQuery);
        Pageable pageable = pageableResolver.resolve(page, size, sort);
        return productService.list(filter, pageable);
    }

    @GetMapping("/search")
    @Operation(
            summary = "Search products by name",
            description = "Returns up to 50 products matching the query. Uses trigram similarity.")
    public List<ProductDto> search(
            @Parameter(description = "Search query (min 2 chars)", required = true)
                    @RequestParam("q")
                    String query) {
        return productService.search(query);
    }

    // -------------------------------------------------------------------------
    // Single resource
    // -------------------------------------------------------------------------

    @GetMapping("/{id}")
    @Operation(summary = "Get a product by ID")
    @ApiResponse(responseCode = "200", description = "Product found")
    @ApiResponse(
            responseCode = "404",
            description = "Product not found",
            content =
                    @Content(
                            schema =
                                    @Schema(
                                            implementation =
                                                    com.example.omp.shared.web.error.ProblemDetails
                                                            .class)))
    public ProductDto getById(@PathVariable UUID id) {
        return productService.getById(id);
    }

    @GetMapping("/sku/{sku}")
    @Operation(summary = "Get a product by SKU")
    public ProductDto getBySku(@PathVariable String sku) {
        return productService.getBySku(sku);
    }

    // -------------------------------------------------------------------------
    // Write operations
    // -------------------------------------------------------------------------

    @PostMapping
    @PreAuthorize("hasRole('admin')")
    @Operation(summary = "Create a new product")
    @ApiResponse(responseCode = "201", description = "Product created")
    @ApiResponse(responseCode = "400", description = "Invalid request body")
    @ApiResponse(responseCode = "409", description = "SKU already exists")
    public ResponseEntity<ProductDto> create(
            @Valid @RequestBody CreateProductRequest request, UriComponentsBuilder uriBuilder) {
        ProductDto created = productService.create(request);
        URI location =
                uriBuilder.path("/api/v1/products/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('admin')")
    @Operation(summary = "Replace a product's mutable fields")
    public ProductDto update(
            @PathVariable UUID id, @Valid @RequestBody UpdateProductRequest request) {
        return productService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('admin')")
    @Operation(
            summary = "Deactivate a product",
            description =
                    "Soft delete. The product is marked INACTIVE and remains queryable by ID.")
    @ApiResponse(responseCode = "204", description = "Product deactivated")
    public ResponseEntity<Void> deactivate(@PathVariable UUID id) {
        productService.deactivate(id);
        return ResponseEntity.noContent().build();
    }
}
