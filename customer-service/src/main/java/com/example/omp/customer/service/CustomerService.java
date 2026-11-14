package com.example.omp.customer.service;

import com.example.omp.customer.domain.Address;
import com.example.omp.customer.domain.AddressType;
import com.example.omp.customer.domain.Customer;
import com.example.omp.customer.domain.CustomerPreferences;
import com.example.omp.customer.dto.*;
import com.example.omp.customer.error.AddressNotFoundException;
import com.example.omp.customer.error.CustomerNotFoundException;
import com.example.omp.customer.error.EmailAlreadyExistsException;
import com.example.omp.customer.error.InvalidCustomerDataException;
import com.example.omp.customer.repository.CustomerRepository;
import com.example.omp.customer.repository.CustomerSpecifications;
import com.example.omp.shared.domain.Email;
import com.example.omp.shared.domain.PhoneNumber;
import com.example.omp.shared.web.PageResponse;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic for customer profiles.
 *
 * <p>All public methods return DTOs (never entities) and throw domain exceptions for business rule
 * violations.
 */
@Service
public class CustomerService {

    private static final Logger log = LoggerFactory.getLogger(CustomerService.class);

    private final CustomerRepository customerRepository;

    public CustomerService(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    // -------------------------------------------------------------------------
    // Reads
    // -------------------------------------------------------------------------

    /** Returns found customers in first-requested order, omitting missing IDs and duplicates. */
    @Transactional(readOnly = true)
    public List<CustomerDto> getBatch(List<UUID> ids) {
        if (ids == null
                || ids.isEmpty()
                || ids.size() > 100
                || ids.stream().anyMatch(java.util.Objects::isNull))
            throw new InvalidCustomerDataException("Batch must contain 1 to 100 non-null IDs");
        var found =
                customerRepository.findByIdIn(ids).stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        Customer::getId, CustomerMapper::toDto));
        return ids.stream().distinct().filter(found::containsKey).map(found::get).toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<CustomerDto> list(CustomerFilter filter, Pageable pageable) {
        Specification<Customer> spec = CustomerSpecifications.fromFilter(filter);
        Page<Customer> page = customerRepository.findAll(spec, pageable);
        return PageResponse.from(page, CustomerMapper::toDto);
    }

    @Transactional(readOnly = true)
    public CustomerDto getById(UUID id) {
        Customer customer =
                customerRepository
                        .findByIdWithDetails(id)
                        .orElseThrow(() -> new CustomerNotFoundException(id));
        return CustomerMapper.toDto(customer);
    }

    @Transactional(readOnly = true)
    public CustomerDto getByEmail(String rawEmail) {
        Email email = Email.of(rawEmail);
        Customer customer =
                customerRepository
                        .findByEmailWithDetails(email)
                        .orElseThrow(() -> new CustomerNotFoundException(rawEmail));
        return CustomerMapper.toDto(customer);
    }

    /**
     * Returns the customer linked to the given Keycloak sub. Used by the {@code GET /customers/me}
     * endpoint.
     */
    @Transactional(readOnly = true)
    public CustomerDto getByKeycloakSub(String sub) {
        Customer customer =
                customerRepository
                        .findByKeycloakSub(sub)
                        .orElseThrow(() -> new CustomerNotFoundException("keycloak_sub=" + sub));
        return CustomerMapper.toDto(customer);
    }

    // -------------------------------------------------------------------------
    // Writes
    // -------------------------------------------------------------------------

    @Transactional
    public CustomerDto create(CreateCustomerRequest request) {
        Email email = Email.of(request.email());

        if (customerRepository.existsByEmail(email)) {
            throw new EmailAlreadyExistsException(email.value());
        }

        Customer customer = CustomerMapper.fromCreateRequest(request);

        // Addresses
        if (request.addresses() != null) {
            for (CreateAddressRequest addressReq : request.addresses()) {
                Address address = AddressMapper.fromCreateRequest(addressReq);
                if (Boolean.TRUE.equals(addressReq.isDefault())) {
                    customer.addAddress(address);
                    customer.setDefaultAddress(address);
                } else {
                    customer.addAddress(address);
                }
            }
        }

        // Preferences
        if (request.preferences() != null) {
            var prefs = request.preferences();
            CustomerPreferences preferences =
                    new CustomerPreferences(
                            prefs.locale() != null ? prefs.locale() : "en-US",
                            prefs.currency() != null ? prefs.currency() : "USD");
            if (prefs.marketingOptIn() != null)
                preferences.setMarketingOptIn(prefs.marketingOptIn());
            if (prefs.notificationEmail() != null)
                preferences.setNotificationEmail(prefs.notificationEmail());
            if (prefs.notificationSms() != null)
                preferences.setNotificationSms(prefs.notificationSms());
            customer.setPreferences(preferences);
        }

        Customer saved = customerRepository.save(customer);
        log.info("Created customer id={} email={}", saved.getId(), saved.getEmail());
        return CustomerMapper.toDto(saved);
    }

    @Transactional
    public CustomerDto update(UUID id, UpdateCustomerRequest request) {
        Customer customer =
                customerRepository
                        .findById(id)
                        .orElseThrow(() -> new CustomerNotFoundException(id));

        Email newEmail = Email.of(request.email());
        if (!customer.getEmail().equals(newEmail) && customerRepository.existsByEmail(newEmail)) {
            throw new EmailAlreadyExistsException(newEmail.value());
        }

        customer.setEmail(newEmail);
        customer.setFirstName(request.firstName());
        customer.setLastName(request.lastName());
        customer.setPhone(
                request.phone() != null && !request.phone().isBlank()
                        ? PhoneNumber.of(request.phone())
                        : null);
        customer.setDateOfBirth(request.dateOfBirth());

        log.info("Updated customer id={}", id);
        return CustomerMapper.toDto(customer);
    }

    @Transactional
    public void deactivate(UUID id) {
        Customer customer =
                customerRepository
                        .findById(id)
                        .orElseThrow(() -> new CustomerNotFoundException(id));
        customer.deactivate();
        log.info("Deactivated customer id={}", id);
    }

    @Transactional
    public CustomerDto linkKeycloakSub(UUID id, String keycloakSub) {
        Customer customer =
                customerRepository
                        .findById(id)
                        .orElseThrow(() -> new CustomerNotFoundException(id));

        if (customer.getKeycloakSub() != null && !customer.getKeycloakSub().equals(keycloakSub)) {
            throw new InvalidCustomerDataException(
                    "Customer " + id + " is already linked to a different Keycloak user");
        }

        // Ensure no other customer has this sub
        customerRepository
                .findByKeycloakSub(keycloakSub)
                .ifPresent(
                        other -> {
                            if (!other.getId().equals(id)) {
                                throw new InvalidCustomerDataException(
                                        "Keycloak user "
                                                + keycloakSub
                                                + " is already linked to another customer");
                            }
                        });

        customer.linkKeycloakUser(keycloakSub);
        log.info("Linked customer id={} to keycloak sub={}", id, keycloakSub);
        return CustomerMapper.toDto(customer);
    }

    // -------------------------------------------------------------------------
    // Address operations
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<AddressDto> listAddresses(UUID customerId) {
        Customer customer =
                customerRepository
                        .findByIdWithDetails(customerId)
                        .orElseThrow(() -> new CustomerNotFoundException(customerId));
        return customer.getAddresses().stream().map(CustomerMapper::toAddressDto).toList();
    }

    @Transactional
    public AddressDto addAddress(UUID customerId, CreateAddressRequest request) {
        Customer customer =
                customerRepository
                        .findByIdForUpdate(customerId)
                        .orElseThrow(() -> new CustomerNotFoundException(customerId));

        Address address = AddressMapper.fromCreateRequest(request);
        if (Boolean.TRUE.equals(request.isDefault())) {
            clearDefaultBeforePromotion(customer, address.getAddressType());
        }
        customer.addAddress(address);
        if (Boolean.TRUE.equals(request.isDefault())) {
            customer.setDefaultAddress(address);
        }

        // Flush the managed graph directly so cascade assigns the original address's UUID.
        customerRepository.flush();
        return CustomerMapper.toAddressDto(address);
    }

    @Transactional
    public AddressDto updateAddress(UUID customerId, UUID addressId, CreateAddressRequest request) {
        Customer customer =
                customerRepository
                        .findByIdForUpdate(customerId)
                        .orElseThrow(() -> new CustomerNotFoundException(customerId));

        Address address =
                customer.getAddresses().stream()
                        .filter(a -> a.getId().equals(addressId))
                        .findFirst()
                        .orElseThrow(() -> new AddressNotFoundException(customerId, addressId));

        AddressType type = AddressType.valueOf(request.addressType());
        boolean makeDefault =
                request.isDefault() == null ? address.isDefault() : request.isDefault();
        if (makeDefault) {
            clearDefaultBeforePromotion(customer, type);
        }
        address.setAddressType(type);
        address.setLine1(request.line1());
        address.setLine2(request.line2());
        address.setCity(request.city());
        address.setState(request.state());
        address.setPostalCode(request.postalCode());
        address.setCountry(request.country());

        if (makeDefault) {
            customer.setDefaultAddress(address);
        } else {
            customer.clearDefaultAddress(address);
        }

        return CustomerMapper.toAddressDto(address);
    }

    @Transactional
    public void removeAddress(UUID customerId, UUID addressId) {
        Customer customer =
                customerRepository
                        .findByIdForUpdate(customerId)
                        .orElseThrow(() -> new CustomerNotFoundException(customerId));

        Address address =
                customer.getAddresses().stream()
                        .filter(a -> a.getId().equals(addressId))
                        .findFirst()
                        .orElseThrow(() -> new AddressNotFoundException(customerId, addressId));

        customer.removeAddress(address);
        // orphanRemoval deletes the row.
    }

    private void clearDefaultBeforePromotion(Customer customer, AddressType type) {
        customer.unsetDefaultForType(type);
        // The partial unique index is immediate. Flush demotions to the database before
        // Hibernate queues a promotion or insert; both flushes remain in this transaction.
        customerRepository.flush();
    }

    // -------------------------------------------------------------------------
    // Preferences
    // -------------------------------------------------------------------------

    @Transactional
    public CustomerPreferencesDto setPreferences(UUID customerId, CustomerPreferencesDto request) {
        Customer customer =
                customerRepository
                        .findByIdForUpdate(customerId)
                        .orElseThrow(() -> new CustomerNotFoundException(customerId));

        CustomerPreferences prefs = customer.getPreferences();
        if (prefs == null) {
            prefs = new CustomerPreferences(request.locale(), request.currency());
            customer.setPreferences(prefs);
        } else {
            prefs.setLocale(request.locale());
            prefs.setCurrency(request.currency());
        }
        prefs.setMarketingOptIn(request.marketingOptIn());
        prefs.setNotificationEmail(request.notificationEmail());
        prefs.setNotificationSms(request.notificationSms());

        return CustomerMapper.toPreferencesDto(prefs);
    }

    /** Applies partial preference updates while holding the aggregate row lock. */
    @Transactional
    public CustomerPreferencesDto updatePreferences(
            UUID customerId, UpdatePreferencesRequest request) {
        Customer customer =
                customerRepository
                        .findByIdForUpdate(customerId)
                        .orElseThrow(() -> new CustomerNotFoundException(customerId));
        CustomerPreferences preferences = customer.getPreferences();
        if (preferences == null) {
            preferences = CustomerPreferences.defaults();
            customer.setPreferences(preferences);
        }
        if (request.locale() != null) preferences.setLocale(request.locale());
        if (request.currency() != null) preferences.setCurrency(request.currency());
        if (request.marketingOptIn() != null)
            preferences.setMarketingOptIn(request.marketingOptIn());
        if (request.notificationEmail() != null)
            preferences.setNotificationEmail(request.notificationEmail());
        if (request.notificationSms() != null)
            preferences.setNotificationSms(request.notificationSms());
        return CustomerMapper.toPreferencesDto(preferences);
    }
}
