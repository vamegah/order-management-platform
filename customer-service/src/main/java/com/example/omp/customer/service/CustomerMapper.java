package com.example.omp.customer.service;

import com.example.omp.customer.domain.Address;
import com.example.omp.customer.domain.Customer;
import com.example.omp.customer.domain.CustomerPreferences;
import com.example.omp.customer.dto.AddressDto;
import com.example.omp.customer.dto.CreateCustomerRequest;
import com.example.omp.customer.dto.CustomerDto;
import com.example.omp.customer.dto.CustomerPreferencesDto;
import com.example.omp.customer.dto.CustomerSummaryDto;
import com.example.omp.shared.domain.Email;
import com.example.omp.shared.domain.PhoneNumber;
import java.util.List;

/**
 * Converts between customer domain objects and DTOs.
 *
 * <p>Static methods — mapping is stateless.
 */
public final class CustomerMapper {

    private CustomerMapper() {}

    // -------------------------------------------------------------------------
    // Entity -> DTO
    // -------------------------------------------------------------------------

    /** Full customer DTO, including addresses and preferences. */
    public static CustomerDto toDto(Customer customer) {
        if (customer == null) {
            return null;
        }
        List<AddressDto> addresses =
                customer.getAddresses().stream().map(CustomerMapper::toAddressDto).toList();

        return new CustomerDto(
                customer.getId(),
                customer.getEmail() != null ? customer.getEmail().value() : null,
                customer.getKeycloakSub(),
                customer.getFirstName(),
                customer.getLastName(),
                customer.getPhone() != null ? customer.getPhone().value() : null,
                customer.getDateOfBirth(),
                customer.getStatus().name(),
                addresses,
                toPreferencesDto(customer.getPreferences()),
                customer.getCreatedAt(),
                customer.getUpdatedAt());
    }

    /** Minimal customer summary (id, email, full name). */
    public static CustomerSummaryDto toSummary(Customer customer) {
        if (customer == null) {
            return null;
        }
        return new CustomerSummaryDto(
                customer.getId(),
                customer.getEmail() != null ? customer.getEmail().value() : null,
                customer.getFirstName() + " " + customer.getLastName());
    }

    /** Address DTO. */
    public static AddressDto toAddressDto(Address address) {
        if (address == null) {
            return null;
        }
        return new AddressDto(
                address.getId(),
                address.getAddressType().name(),
                address.getLine1(),
                address.getLine2(),
                address.getCity(),
                address.getState(),
                address.getPostalCode(),
                address.getCountry(),
                address.isDefault());
    }

    /** Preferences DTO. */
    public static CustomerPreferencesDto toPreferencesDto(CustomerPreferences prefs) {
        if (prefs == null) {
            return null;
        }
        return new CustomerPreferencesDto(
                prefs.getLocale(),
                prefs.getCurrency(),
                prefs.isMarketingOptIn(),
                prefs.isNotificationEmail(),
                prefs.isNotificationSms());
    }

    // -------------------------------------------------------------------------
    // Request -> Entity
    // -------------------------------------------------------------------------

    /**
     * Builds a new customer from a create request. Addresses and preferences are added by the
     * service after the customer is saved (to get the generated id).
     */
    public static Customer fromCreateRequest(CreateCustomerRequest request) {
        Email email = Email.of(request.email());
        Customer customer = new Customer(email, request.firstName(), request.lastName());

        if (request.phone() != null && !request.phone().isBlank()) {
            customer.setPhone(PhoneNumber.of(request.phone()));
        }
        if (request.dateOfBirth() != null) {
            customer.setDateOfBirth(request.dateOfBirth());
        }
        return customer;
    }
}
