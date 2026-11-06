package com.example.omp.gateway.graphql.mutation;

import com.example.omp.gateway.clients.OrderClient;
import com.example.omp.gateway.clients.dto.*;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.graphql.data.method.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

@Controller
public class OrderMutationResolver {
    private final OrderClient client;

    public OrderMutationResolver(OrderClient client) {
        this.client = client;
    }

    @MutationMapping
    public OrderClientDto createOrder(@Argument @Valid CreateOrderRequest input) {
        return client.createOrder(input);
    }

    @MutationMapping
    public OrderClientDto placeOrder(@Argument UUID id) {
        return client.placeOrder(id);
    }

    @MutationMapping
    public OrderClientDto cancelOrder(@Argument UUID id, @Argument String reason) {
        return client.cancelOrder(id, new OrderClient.ReasonRequest(reason));
    }

    @MutationMapping
    @PreAuthorize("hasRole('admin')")
    public OrderClientDto confirmOrder(@Argument UUID id, @Argument String reason) {
        return client.confirmOrder(id, new OrderClient.ReasonRequest(reason));
    }

    @MutationMapping
    @PreAuthorize("hasRole('admin')")
    public OrderClientDto shipOrder(@Argument UUID id, @Argument String reason) {
        return client.shipOrder(id, new OrderClient.ReasonRequest(reason));
    }

    @MutationMapping
    @PreAuthorize("hasRole('admin')")
    public OrderClientDto deliverOrder(@Argument UUID id, @Argument String reason) {
        return client.deliverOrder(id, new OrderClient.ReasonRequest(reason));
    }
}
