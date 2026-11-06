package com.example.omp.customer.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.omp.customer.AbstractIntegrationTest;
import com.example.omp.customer.domain.Customer;
import com.example.omp.shared.domain.Email;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class CustomerRepositoryIT extends AbstractIntegrationTest {

    @Autowired private CustomerRepository customerRepository;

    @Test
    void findByEmailIsCaseInsensitive() {
        Optional<Customer> lower = customerRepository.findByEmail(Email.of("alice@omp.local"));
        Optional<Customer> upper = customerRepository.findByEmail(Email.of("ALICE@OMP.LOCAL"));

        assertThat(lower).isPresent();
        assertThat(upper).isPresent();
        assertThat(lower.get().getId()).isEqualTo(upper.get().getId());
    }

    @Test
    void findByKeycloakSubReturnsLinkedCustomer() {
        Optional<Customer> customer = customerRepository.findByKeycloakSub("test-alice");

        assertThat(customer).isPresent();
        assertThat(customer.get().getEmail().value()).isEqualTo("alice@omp.local");
    }

    @Test
    void findByIdWithDetailsLoadsAddressesAndPreferences() {
        var customer =
                customerRepository.findByIdWithDetails(
                        java.util.UUID.fromString("00000000-0000-0000-0000-000000000101"));

        assertThat(customer).isPresent();
        // Alice has 2 addresses and preferences
        assertThat(customer.get().getAddresses()).hasSize(2);
        assertThat(customer.get().getPreferences()).isNotNull();
        assertThat(customer.get().getPreferences().getLocale()).isEqualTo("en-US");
    }

    @Test
    void searchByNameMatchesFirstOrLastName() {
        var page =
                customerRepository.searchByName(
                        "%ali%", org.springframework.data.domain.PageRequest.of(0, 10));

        assertThat(page.getContent()).isNotEmpty();
        assertThat(page.getContent())
                .extracting(c -> c.getEmail().value())
                .contains("alice@omp.local");
    }

    @Test
    void findByIdWithDetailsReturnsEmptyForUnknown() {
        Optional<Customer> customer =
                customerRepository.findByIdWithDetails(java.util.UUID.randomUUID());
        assertThat(customer).isEmpty();
    }
}
