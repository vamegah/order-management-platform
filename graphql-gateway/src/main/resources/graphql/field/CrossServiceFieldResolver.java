package com.example.omp.gateway.graphql.field;

import com.example.omp.gateway.clients.*;
import com.example.omp.gateway.clients.dto.*;
import com.example.omp.gateway.graphql.mapper.*;
import com.example.omp.gateway.graphql.type.*;
import org.dataloader.DataLoader;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Resolves cross-service fields on GraphQL types.
 *
 * <p>Each method handles one "typeName.field" pair where the value comes
 * from a different service than the parent type.
 */
@Controller
public class CrossServiceFieldResolver {

    private final CustomerClient customerClient;
    private final OrderClient orderClient;
    private final ProductClient productClient;

    public CrossServiceFieldResolver(CustomerClient customerClient,
                                      OrderClient orderClient,
                                      ProductClient productClient) {
        this.customerClient = customerClient;
        this.orderClient = orderClient;
        this.productClient = productClient;
    }

    /**
     * Customer.orders — calls order-service filtered by customer ID.
     */
    @SchemaMapping(typeName = "Customer", field = "orders")
    public List<OrderGql> customerOrders(CustomerGql customer, @Argument int limit) {
        OrderPageDto page = orderClient.listOrders(
            customer.id(), null, null, 0, limit, null);
        return page.content().stream().map(OrderMapper::toGql).toList();
    }

    /**
     * Order.customer — calls customer-service.
     */
    @SchemaMapping(typeName = "Order", field = "customer")
    public CompletableFuture<CustomerGql> orderCustomer(OrderGql order,
                                                         org.dataloader.DataLoader<UUID, CustomerGql> customerLoader) {
        return customerLoader.load(order.customerId());
    }

    /**
     * OrderLine.product — calls product-service.
     */
    @SchemaMapping(typeName = "OrderLine", field = "product")
    public CompletableFuture<ProductGql> orderLineProduct(OrderLineGql line,
                                                           org.dataloader.DataLoader<UUID, ProductGql> productLoader) {
        return productLoader.load(line.productId());
    }
}
