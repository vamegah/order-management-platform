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
        var after = placedDate(filter, "placedAfter");
        var before = placedDate(filter, "placedBefore");
        if (after != null && before != null && after.isAfter(before))
            throw new IllegalArgumentException("Invalid placement interval");
        if (after != null || before != null)
            return client.listOrdersByPlacement(
                    id(filter, "customerId"),
                    text(filter, "status"),
                    text(filter, "orderNumber"),
                    page(page),
                    size(size),
                    null,
                    after,
                    before);
        return client.listOrders(
                id(filter, "customerId"),
                text(filter, "status"),
                text(filter, "orderNumber"),
                page(page),
                size(size),
                null);
    }

    private static java.time.Instant placedDate(Map<String, Object> filter, String name) {
        if (filter == null || filter.get(name) == null) return null;
        Object value = filter.get(name);
        if (value instanceof java.time.Instant instant) return instant;
        try {
            return java.time.OffsetDateTime.parse(value.toString()).toInstant();
        } catch (java.time.DateTimeException failure) {
            throw new IllegalArgumentException("Invalid placement date");
        }
    }
}
