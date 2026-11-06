package com.example.omp.product.web;

import com.example.omp.product.dto.CategoryDto;
import com.example.omp.product.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for product categories.
 *
 * <p>Read-only in Phase 1. Category management (create/update/delete) is a future addition — for
 * now categories are seeded (see V2__seed_data.sql) and only queried via this API.
 */
@RestController
@RequestMapping("/api/v1/categories")
@Tag(name = "Categories", description = "Product category operations")
@SecurityRequirement(name = "bearerAuth")
public class CategoryController {

    private final ProductService productService;

    public CategoryController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    @Operation(
            summary = "List all categories",
            description =
                    "Returns a flat list of all categories. Clients build the tree from parent references.")
    public List<CategoryDto> list() {
        return productService.listCategories();
    }

    @GetMapping("/{slug}")
    @Operation(summary = "Get a category by slug")
    public CategoryDto getBySlug(@PathVariable String slug) {
        return productService.getCategoryBySlug(slug);
    }
}
