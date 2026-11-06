package com.example.omp.gateway.graphql.mutation;

import com.example.omp.gateway.clients.CustomerClient;
import com.example.omp.gateway.clients.dto.*;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.graphql.data.method.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

@Controller
@PreAuthorize("hasRole('admin')")
public class CustomerMutationResolver {
    private final CustomerClient client;

    public CustomerMutationResolver(CustomerClient client) {
        this.client = client;
    }

    @MutationMapping
    public CustomerClientDto createCustomer(@Argument @Valid CreateCustomerRequest input) {
        return client.createCustomer(input);
    }

    @MutationMapping
    public CustomerClientDto updateCustomer(
            @Argument UUID id, @Argument @Valid UpdateCustomerRequest input) {
        return client.updateCustomer(id, input);
    }

    @MutationMapping
    public CustomerClientDto deactivateCustomer(@Argument UUID id) {
        client.deactivateCustomer(id);
        return client.getCustomer(id);
    }

    @MutationMapping
    public AddressClientDto addCustomerAddress(
            @Argument UUID customerId, @Argument @Valid CreateAddressRequest input) {
        return client.addAddress(customerId, input);
    }

    @MutationMapping
    public PreferencesClientDto setCustomerPreferences(
            @Argument UUID customerId, @Argument PreferencesClientDto input) {
        return client.setPreferences(customerId, input);
    }
}
