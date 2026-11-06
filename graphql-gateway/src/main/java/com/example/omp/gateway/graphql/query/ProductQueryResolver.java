package com.example.omp.gateway.graphql.query;

import static com.example.omp.gateway.graphql.query.QueryInputs.*;

import com.example.omp.gateway.clients.ProductClient;
import com.example.omp.gateway.clients.dto.*;
import java.util.*;
import org.springframework.graphql.data.method.annotation.*;
import org.springframework.stereotype.Controller;

@Controller
public class ProductQueryResolver {
    private final ProductClient client;

    public ProductQueryResolver(ProductClient client) {
        this.client = client;
    }

    @QueryMapping
    public ProductClientDto product(@Argument UUID id) {
        return client.getProduct(id);
    }

    @QueryMapping
    public ProductClientDto productBySku(@Argument String sku) {
        return client.getProductBySku(sku);
    }

    @QueryMapping
    public List<CategoryClientDto> categories() {
        return client.listCategories();
    }

    @QueryMapping
    public ProductPageDto products(
            @Argument Map<String, Object> filter, @Argument Integer page, @Argument Integer size) {
        return client.listProducts(
                text(filter, "categorySlug"),
                id(filter, "categoryId"),
                text(filter, "status"),
                text(filter, "nameQuery"),
                page(page),
                size(size),
                null);
    }
}
