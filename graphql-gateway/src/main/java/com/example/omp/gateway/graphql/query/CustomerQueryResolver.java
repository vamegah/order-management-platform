package com.example.omp.gateway.graphql.query;

import static com.example.omp.gateway.graphql.query.QueryInputs.*;

import com.example.omp.gateway.clients.CustomerClient;
import com.example.omp.gateway.clients.dto.*;
import java.util.*;
import org.springframework.graphql.data.method.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

@Controller
public class CustomerQueryResolver {
    private final CustomerClient client;

    public CustomerQueryResolver(CustomerClient client) {
        this.client = client;
    }

    @QueryMapping
    public CustomerClientDto customer(@Argument UUID id) {
        return client.getCustomer(id);
    }

    @QueryMapping
    @PreAuthorize("hasRole('admin')")
    public CustomerClientDto customerByEmail(@Argument String email) {
        return client.getCustomerByEmail(email);
    }

    @QueryMapping
    public CustomerClientDto me() {
        return client.getCurrentCustomer();
    }

    @QueryMapping
    @PreAuthorize("hasRole('admin')")
    public CustomerPageDto customers(
            @Argument Map<String, Object> filter, @Argument Integer page, @Argument Integer size) {
        return client.listCustomers(
                text(filter, "status"),
                text(filter, "nameQuery"),
                text(filter, "emailDomain"),
                page(page),
                size(size),
                null);
    }
}
