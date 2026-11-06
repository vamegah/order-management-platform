package com.example.omp.gateway.graphql.query;

import static com.example.omp.gateway.graphql.query.QueryInputs.*;

import com.example.omp.gateway.clients.OrderClient;
import com.example.omp.gateway.clients.dto.*;
import java.util.*;
import org.springframework.graphql.data.method.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

@Controller
public class OrderQueryResolver {
    private final OrderClient client;

    public OrderQueryResolver(OrderClient client) {
        this.client = client;
    }

    @QueryMapping
    public OrderClientDto order(@Argument UUID id) {
        return client.getOrder(id, true);
    }

    @QueryMapping
    public OrderClientDto orderByNumber(@Argument String orderNumber) {
        return client.getOrderByNumber(orderNumber, true);
    }

    @QueryMapping
    @PreAuthorize("hasRole('admin')")
    public OrderPageDto orders(
            @Argument Map<String, Object> filter, @Argument Integer page, @Argument Integer size) {
        if (text(filter, "placedAfter") != null || text(filter, "placedBefore") != null)
            throw new IllegalArgumentException("Date filters are not supported by the order API");
        return client.listOrders(
                id(filter, "customerId"),
                text(filter, "status"),
                text(filter, "orderNumber"),
                page(page),
                size(size),
                null);
    }
}
