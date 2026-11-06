package com.example.omp.gateway.graphql.field;

import com.example.omp.gateway.clients.OrderClient;
import com.example.omp.gateway.clients.dto.*;
import com.example.omp.gateway.graphql.query.QueryInputs;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.dataloader.DataLoader;
import org.springframework.graphql.data.method.annotation.*;
import org.springframework.stereotype.Controller;

@Controller
public class CrossServiceFieldResolver {
    private final OrderClient orders;

    public CrossServiceFieldResolver(OrderClient orders) {
        this.orders = orders;
    }

    @SchemaMapping(typeName = "Order", field = "customer")
    public CompletableFuture<CustomerClientDto> customer(
            OrderClientDto order, DataLoader<UUID, CustomerClientDto> loader) {
        return loader.load(order.customerId());
    }

    @SchemaMapping(typeName = "OrderLine", field = "product")
    public CompletableFuture<ProductClientDto> product(
            OrderLineClientDto line, DataLoader<UUID, ProductClientDto> loader) {
        return loader.load(line.productId());
    }

    @SchemaMapping(typeName = "Customer", field = "orders")
    public List<OrderClientDto> orders(CustomerClientDto customer, @Argument Integer limit) {
        return orders.listOrders(customer.id(), null, null, 0, QueryInputs.size(limit), null)
                .content();
    }
}
