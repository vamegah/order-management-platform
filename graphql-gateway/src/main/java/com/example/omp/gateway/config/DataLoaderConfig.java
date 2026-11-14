package com.example.omp.gateway.config;

import com.example.omp.gateway.clients.CustomerClient;
import com.example.omp.gateway.clients.ProductClient;
import com.example.omp.gateway.clients.dto.CustomerClientDto;
import com.example.omp.gateway.clients.dto.ProductClientDto;
import java.util.HashMap;
import java.util.UUID;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.execution.BatchLoaderRegistry;
import reactor.core.publisher.Mono;

/** Deduplicates downstream reads per GraphQL request, preserving the caller security context. */
@Configuration
public class DataLoaderConfig {
    public DataLoaderConfig(
            BatchLoaderRegistry registry, ProductClient products, CustomerClient customers) {
        registry.forTypePair(UUID.class, ProductClientDto.class)
                .withOptions(options -> options.setMaxBatchSize(100))
                .registerMappedBatchLoader(
                        (ids, environment) -> {
                            var results = new HashMap<UUID, ProductClientDto>();
                            for (var product : products.getProducts(java.util.List.copyOf(ids)))
                                results.put(product.id(), product);
                            return Mono.just(results);
                        });
        registry.forTypePair(UUID.class, CustomerClientDto.class)
                .withOptions(options -> options.setMaxBatchSize(100))
                .registerMappedBatchLoader(
                        (ids, environment) -> {
                            var results = new HashMap<UUID, CustomerClientDto>();
                            for (var customer : customers.getCustomers(java.util.List.copyOf(ids)))
                                results.put(customer.id(), customer);
                            return Mono.just(results);
                        });
        registry.forTypePair(
                        String.class, com.example.omp.gateway.clients.dto.CategoryClientDto.class)
                .withOptions(options -> options.setMaxBatchSize(100))
                .registerMappedBatchLoader(
                        (slugs, environment) -> {
                            var results =
                                    new HashMap<
                                            String,
                                            com.example.omp.gateway.clients.dto
                                                    .CategoryClientDto>();
                            for (var category : products.listCategories()) {
                                if (slugs.contains(category.slug()))
                                    results.put(category.slug(), category);
                            }
                            return Mono.just(results);
                        });
    }
}
