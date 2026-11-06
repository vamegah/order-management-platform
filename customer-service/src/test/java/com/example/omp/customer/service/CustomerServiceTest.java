package com.example.omp.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.example.omp.customer.domain.Customer;
import com.example.omp.customer.domain.CustomerPreferences;
import com.example.omp.customer.domain.CustomerStatus;
import com.example.omp.customer.dto.CreateCustomerRequest;
import com.example.omp.customer.dto.CustomerDto;
import com.example.omp.customer.dto.CustomerPreferencesDto;
import com.example.omp.customer.error.CustomerNotFoundException;
import com.example.omp.customer.error.EmailAlreadyExistsException;
import com.example.omp.customer.repository.CustomerRepository;
import com.example.omp.shared.domain.Email;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

    @Test
    void batchPreservesOrderDeduplicatesAndOmitsMissingCustomers() {
        UUID firstId = UUID.randomUUID(), secondId = UUID.randomUUID(), missing = UUID.randomUUID();
        var first = new Customer(Email.of("first@example.com"), "First", "Customer");
        var second = new Customer(Email.of("second@example.com"), "Second", "Customer");
        org.springframework.test.util.ReflectionTestUtils.setField(first, "id", firstId);
        org.springframework.test.util.ReflectionTestUtils.setField(second, "id", secondId);
        var ids = List.of(secondId, firstId, missing, secondId);
        when(customerRepository.findByIdIn(ids)).thenReturn(List.of(first, second));
        assertThat(customerService.getBatch(ids))
                .extracting(CustomerDto::id)
                .containsExactly(secondId, firstId);
        verify(customerRepository).findByIdIn(ids);
    }

    @Test
    void oversizedBatchIsRejectedBeforeDatabaseAccess() {
        assertThatThrownBy(
                        () ->
                                customerService.getBatch(
                                        java.util.Collections.nCopies(101, UUID.randomUUID())))
                .isInstanceOf(com.example.omp.customer.error.InvalidCustomerDataException.class);
        verifyNoInteractions(customerRepository);
    }

    @Mock private CustomerRepository customerRepository;

    @InjectMocks private CustomerService customerService;

    @Test
    void creatingPreferencesHonorsOptInAndNotificationChoices() {
        UUID id = UUID.randomUUID();
        var customer = new Customer(Email.of("prefs@example.com"), "Preferences", "Customer");
        when(customerRepository.findByIdForUpdate(id)).thenReturn(Optional.of(customer));
        var request = new CustomerPreferencesDto("de-DE", "EUR", true, false, true);
        assertThat(customerService.setPreferences(id, request)).isEqualTo(request);
        assertThat(customer.getPreferences().getCustomer()).isSameAs(customer);
    }

    @Test
    void replacingPreferencesCanRevokeOptInAndSms() {
        UUID id = UUID.randomUUID();
        var customer = new Customer(Email.of("prefs@example.com"), "Preferences", "Customer");
        var preferences = new CustomerPreferences("de-DE", "EUR");
        preferences.setMarketingOptIn(true);
        preferences.setNotificationSms(true);
        customer.setPreferences(preferences);
        when(customerRepository.findByIdForUpdate(id)).thenReturn(Optional.of(customer));
        var request = new CustomerPreferencesDto("en-US", "USD", false, true, false);
        assertThat(customerService.setPreferences(id, request)).isEqualTo(request);
        assertThat(customer.getPreferences()).isSameAs(preferences);
    }

    // -------------------------------------------------------------------------
    // create
    // -------------------------------------------------------------------------

    @Test
    void createThrowsWhenEmailAlreadyExists() {
        var request = newRequest("existing@example.com");

        when(customerRepository.existsByEmail(any(Email.class))).thenReturn(true);

        assertThatThrownBy(() -> customerService.create(request))
                .isInstanceOf(EmailAlreadyExistsException.class)
                .hasMessageContaining("existing@example.com");

        verify(customerRepository, never()).save(any());
    }

    @Test
    void createNormalizesEmailToLowercase() {
        var request = newRequest("MixedCase@Example.COM");

        when(customerRepository.existsByEmail(any(Email.class))).thenReturn(false);
        when(customerRepository.save(any(Customer.class))).thenAnswer(inv -> inv.getArgument(0));

        CustomerDto result = customerService.create(request);

        assertThat(result.email()).isEqualTo("mixedcase@example.com");
    }

    @Test
    void createPersistsAndReturnsDto() {
        var request = newRequest("new@example.com");

        when(customerRepository.existsByEmail(any(Email.class))).thenReturn(false);
        when(customerRepository.save(any(Customer.class))).thenAnswer(inv -> inv.getArgument(0));

        CustomerDto result = customerService.create(request);

        assertThat(result).isNotNull();
        assertThat(result.email()).isEqualTo("new@example.com");
        assertThat(result.firstName()).isEqualTo("New");
        assertThat(result.lastName()).isEqualTo("Customer");
        assertThat(result.status()).isEqualTo("ACTIVE");
    }

    // -------------------------------------------------------------------------
    // getById
    // -------------------------------------------------------------------------

    @Test
    void getByIdThrowsWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(customerRepository.findByIdWithDetails(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> customerService.getById(id))
                .isInstanceOf(CustomerNotFoundException.class)
                .hasMessageContaining(id.toString());
    }

    // -------------------------------------------------------------------------
    // getByKeycloakSub
    // -------------------------------------------------------------------------

    @Test
    void getByKeycloakSubThrowsWhenNotLinked() {
        when(customerRepository.findByKeycloakSub("unknown-sub")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> customerService.getByKeycloakSub("unknown-sub"))
                .isInstanceOf(CustomerNotFoundException.class);
    }

    // -------------------------------------------------------------------------
    // deactivate
    // -------------------------------------------------------------------------

    @Test
    void deactivateMarksCustomerInactive() {
        UUID id = UUID.randomUUID();
        Customer customer = new Customer(Email.of("test@example.com"), "T", "U");
        when(customerRepository.findById(id)).thenReturn(Optional.of(customer));

        customerService.deactivate(id);

        assertThat(customer.getStatus()).isEqualTo(CustomerStatus.INACTIVE);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private CreateCustomerRequest newRequest(String email) {
        return new CreateCustomerRequest(email, "New", "Customer", null, null, List.of(), null);
    }
}
