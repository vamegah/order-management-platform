package com.example.omp.gateway.graphql.query;

import com.example.omp.gateway.clients.ProductClient;
import com.example.omp.gateway.clients.dto.ProductPageDto;
import com.example.omp.gateway.clients.dto.ProductClientDto;
import com.example.omp.gateway.graphql.type.*;
import com.example.omp.gateway.graphql.mapper.ProductMapper;
import graphql.schema.DataFetchingEnvironment;
import org.dataloader.DataLoader;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Controller
public class ProductQueryResolver {

    private final ProductClient productClient;

    public ProductQueryResolver(ProductClient productClient) {
        this.productClient = productClient;
    }

    @QueryMapping
    public ProductGql product(@Argument String id,
                               DataFetchingEnvironment env) {
        UUID productId = UUID.fromString(id);
        DataLoader<UUID, ProductGql> loader = env.getDataLoader("productLoader");
        return loader != null ? loader.load(productId).join()
                               : ProductMapper.toGql(productClient.getProduct(productId));
    }

    @QueryMapping
    public ProductGql productBySku(@Argument String sku) {
        return ProductMapper.toGql(productClient.getProductBySku(sku));
    }

    @QueryMapping
    public ProductPageGql products(@Argument ProductFilterInput filter,
                                    @Argument Integer page,
                                    @Argument Integer size) {
        ProductPageDto dto = productClient.listProducts(
            filter != null ? filter.categorySlug() : null,
            filter != null ? parseUuid(filter.categoryId()) : null,
            filter != null ? filter.status() : null,
            filter != null ? filter.nameQuery() : null,
            page != null ? page : 0,
            size != null ? size : 20,
            null);
        return ProductMapper.toGqlPage(dto);
    }

    @QueryMapping
    public java.util.List<CategoryGql> categories() {
        return productClient.listCategories().stream()
            .map(ProductMapper::toGqlCategory)
            .toList();
    }
}
