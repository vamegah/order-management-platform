package com.example.omp.gateway.graphql.mutation;

import com.example.omp.gateway.clients.ProductClient;
import com.example.omp.gateway.clients.dto.*;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.graphql.data.method.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

@Controller
@PreAuthorize("hasRole('admin')")
public class ProductMutationResolver {
    private final ProductClient client;

    public ProductMutationResolver(ProductClient client) {
        this.client = client;
    }

    @MutationMapping
    public ProductClientDto createProduct(@Argument @Valid CreateProductRequest input) {
        return client.createProduct(input);
    }

    @MutationMapping
    public ProductClientDto updateProduct(
            @Argument UUID id, @Argument @Valid UpdateProductRequest input) {
        return client.updateProduct(id, input);
    }

    @MutationMapping
    public ProductClientDto deactivateProduct(@Argument UUID id) {
        client.deactivateProduct(id);
        return client.getProduct(id);
    }
}
