package com.example.omp.customer.domain;

import static org.assertj.core.api.Assertions.*;

import com.example.omp.shared.domain.Email;
import org.junit.jupiter.api.Test;

class CustomerAddressTest {
    @Test
    void foreignAddressCannotChangeEitherOwnersDefault() {
        var first = customer("first");
        var second = customer("second");
        var owned = address(AddressType.SHIPPING);
        var foreign = address(AddressType.SHIPPING);
        first.addAddress(owned);
        second.addAddress(foreign);
        first.setDefaultAddress(owned);
        second.setDefaultAddress(foreign);
        assertThatThrownBy(() -> first.setDefaultAddress(foreign))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> first.clearDefaultAddress(foreign))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(owned.isDefault()).isTrue();
        assertThat(foreign.isDefault()).isTrue();
    }

    @Test
    void defaultsAreScopedByTypeAndRemovalBreaksOwnership() {
        var customer = customer("defaults");
        var billing = address(AddressType.BILLING);
        var first = address(AddressType.SHIPPING);
        var replacement = address(AddressType.SHIPPING);
        customer.addAddress(billing);
        customer.addAddress(first);
        customer.addAddress(replacement);
        customer.setDefaultAddress(billing);
        customer.setDefaultAddress(first);
        customer.setDefaultAddress(replacement);
        assertThat(billing.isDefault()).isTrue();
        assertThat(first.isDefault()).isFalse();
        assertThat(replacement.isDefault()).isTrue();
        customer.clearDefaultAddress(replacement);
        assertThat(replacement.isDefault()).isFalse();
        customer.removeAddress(replacement);
        assertThat(replacement.getCustomer()).isNull();
        assertThat(customer.getAddresses()).containsExactly(billing, first);
    }

    private Customer customer(String name) {
        return new Customer(Email.of(name + "@example.com"), name, "Customer");
    }

    private Address address(AddressType type) {
        return new Address(type, "Main St", null, "Portland", "OR", "97201", "US");
    }
}
