package com.example.omp.customer.service;

import com.example.omp.customer.domain.Address;
import com.example.omp.customer.domain.AddressType;
import com.example.omp.customer.dto.AddressDto;
import com.example.omp.customer.dto.CreateAddressRequest;

/**
 * Converts between address entities and DTOs.
 *
 * <p>Delegates to {@link CustomerMapper} for output mapping; adds input mapping (request → entity).
 */
public final class AddressMapper {

    private AddressMapper() {}

    public static AddressDto toDto(Address address) {
        return CustomerMapper.toAddressDto(address);
    }

    public static Address fromCreateRequest(CreateAddressRequest request) {
        AddressType type = AddressType.valueOf(request.addressType());
        Address address =
                new Address(
                        type,
                        request.line1(),
                        request.line2(),
                        request.city(),
                        request.state(),
                        request.postalCode(),
                        request.country());
        // Note: is_default is handled by Customer.addAddress/setDefaultAddress
        // to keep the invariant consistent.
        return address;
    }
}
