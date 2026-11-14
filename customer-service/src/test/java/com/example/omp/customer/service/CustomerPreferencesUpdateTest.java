package com.example.omp.customer.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.omp.customer.domain.Customer;
import com.example.omp.customer.domain.CustomerPreferences;
import com.example.omp.customer.dto.UpdatePreferencesRequest;
import com.example.omp.customer.error.CustomerNotFoundException;
import com.example.omp.customer.repository.CustomerRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CustomerPreferencesUpdateTest {
    private final CustomerRepository repository = mock(CustomerRepository.class);
    private final CustomerService service = new CustomerService(repository);
    private final UUID customerId = UUID.randomUUID();

    private Customer customer(CustomerPreferences preferences) {
        var customer = mock(Customer.class);
        when(repository.findByIdForUpdate(customerId)).thenReturn(Optional.of(customer));
        when(customer.getPreferences()).thenReturn(preferences);
        return customer;
    }

    @Test
    void omittedFieldsCreateTheDocumentedDefaults() {
        var customer = customer(null);
        var result =
                service.updatePreferences(
                        customerId, new UpdatePreferencesRequest(null, null, null, null, null));
        assertThat(result.locale()).isEqualTo("en-US");
        assertThat(result.currency()).isEqualTo("USD");
        assertThat(result.notificationEmail()).isTrue();
        assertThat(result.notificationSms()).isFalse();
        assertThat(result.marketingOptIn()).isFalse();
        verify(customer).setPreferences(any(CustomerPreferences.class));
        verify(repository).findByIdForUpdate(customerId);
    }

    @Test
    void suppliedFieldsIncludingExplicitFalseReplaceTheDefaults() {
        customer(null);
        var result =
                service.updatePreferences(
                        customerId,
                        new UpdatePreferencesRequest("fr-FR", "EUR", true, false, true));
        assertThat(result.locale()).isEqualTo("fr-FR");
        assertThat(result.currency()).isEqualTo("EUR");
        assertThat(result.marketingOptIn()).isTrue();
        assertThat(result.notificationEmail()).isFalse();
        assertThat(result.notificationSms()).isTrue();
    }

    @Test
    void partialUpdatesPreserveUnspecifiedExistingPreferences() {
        var preferences = new CustomerPreferences("fr-FR", "EUR");
        preferences.setMarketingOptIn(true);
        preferences.setNotificationEmail(false);
        preferences.setNotificationSms(true);
        var customer = customer(preferences);
        var result =
                service.updatePreferences(
                        customerId, new UpdatePreferencesRequest(null, "GBP", false, null, null));
        assertThat(result.locale()).isEqualTo("fr-FR");
        assertThat(result.currency()).isEqualTo("GBP");
        assertThat(result.marketingOptIn()).isFalse();
        assertThat(result.notificationEmail()).isFalse();
        assertThat(result.notificationSms()).isTrue();
        verify(customer, never()).setPreferences(any());
    }

    @Test
    void missingCustomersDoNotCreateAnUnownedPreferenceRecord() {
        when(repository.findByIdForUpdate(customerId)).thenReturn(Optional.empty());
        assertThatThrownBy(
                        () ->
                                service.updatePreferences(
                                        customerId,
                                        new UpdatePreferencesRequest(null, null, null, null, null)))
                .isInstanceOf(CustomerNotFoundException.class);
    }
}
