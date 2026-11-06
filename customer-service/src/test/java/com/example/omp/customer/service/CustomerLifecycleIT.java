package com.example.omp.customer.service;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.*;

import com.example.omp.customer.AbstractIntegrationTest;
import com.example.omp.customer.TestSecurityConfig;
import com.example.omp.customer.dto.*;
import com.example.omp.customer.error.*;
import com.example.omp.customer.repository.CustomerRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Each service call commits independently, so assertions observe real persisted state. */
class CustomerLifecycleIT extends AbstractIntegrationTest {
    @Autowired CustomerService customers;
    @Autowired JdbcTemplate jdbc;
    @Autowired CustomerRepository repository;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

    @Test
    void createsCompleteProfileWithOwnedAddressesAndPreferences() {
        var created =
                customers.create(
                        new CreateCustomerRequest(
                                "Full.Profile@Example.COM",
                                "Full",
                                "Profile",
                                "+1-555-0199",
                                LocalDate.of(1990, 1, 15),
                                List.of(
                                        address("BILLING", "Billing", true),
                                        address("SHIPPING", "Shipping", true)),
                                new CreateCustomerRequest.CreatePreferencesRequest(
                                        "de-DE", "EUR", true, false, true)));
        var stored = customers.getByEmail("FULL.PROFILE@EXAMPLE.COM");
        assertThat(stored.id()).isEqualTo(created.id());
        assertThat(stored.email()).isEqualTo("full.profile@example.com");
        assertThat(stored.phone()).isEqualTo("+1-555-0199");
        assertThat(stored.dateOfBirth()).isEqualTo(LocalDate.of(1990, 1, 15));
        assertThat(stored.createdAt()).isNotNull();
        assertThat(stored.addresses())
                .hasSize(2)
                .allSatisfy(
                        a -> {
                            assertThat(a.id()).isNotNull();
                            assertThat(a.isDefault()).isTrue();
                            assertThat(a.country()).isEqualTo("US");
                        });
        assertThat(stored.preferences())
                .isEqualTo(new CustomerPreferencesDto("de-DE", "EUR", true, false, true));
    }

    @Test
    void profileReplacementCommitsAndDuplicateEmailRollsBack() {
        var first = create("profile-first");
        var second = create("profile-second");
        customers.update(
                first.id(),
                new UpdateCustomerRequest(
                        "Updated@Example.COM",
                        "Updated",
                        "Name",
                        "+1-555-0123",
                        LocalDate.of(1985, 6, 1)));
        var stored = customers.getById(first.id());
        assertThat(stored.email()).isEqualTo("updated@example.com");
        assertThat(stored.firstName()).isEqualTo("Updated");
        assertThat(stored.phone()).isEqualTo("+1-555-0123");
        assertThatThrownBy(
                        () ->
                                customers.update(
                                        first.id(),
                                        new UpdateCustomerRequest(
                                                second.email().toUpperCase(java.util.Locale.ROOT),
                                                "Wrong",
                                                "Name",
                                                null,
                                                null)))
                .isInstanceOf(EmailAlreadyExistsException.class);
        assertThat(customers.getById(first.id()).firstName()).isEqualTo("Updated");
        customers.update(
                first.id(),
                new UpdateCustomerRequest("updated@example.com", "Updated", "Name", "", null));
        assertThat(customers.getById(first.id()).phone()).isNull();
        customers.deactivate(first.id());
        assertThat(customers.getById(first.id()).status()).isEqualTo("INACTIVE");
    }

    @Test
    void preferenceUpsertPersistsEveryFlagOnCreationAndReplacement() {
        var customer = create("preferences");
        var first = new CustomerPreferencesDto("fr-FR", "EUR", true, false, true);
        assertThat(customers.setPreferences(customer.id(), first)).isEqualTo(first);
        assertThat(customers.getById(customer.id()).preferences()).isEqualTo(first);
        var second = new CustomerPreferencesDto("en-US", "USD", false, true, false);
        assertThat(customers.setPreferences(customer.id(), second)).isEqualTo(second);
        assertThat(customers.getById(customer.id()).preferences()).isEqualTo(second);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from customers.customer_preferences where customer_id = ?",
                                Integer.class,
                                customer.id()))
                .isEqualTo(1);
    }

    @Test
    void addingAddressReturnsItsPersistedIdentifier() {
        var customer = create("address-id");
        var added = customers.addAddress(customer.id(), address("SHIPPING", "First", false));
        assertThat(added.id()).isNotNull();
        assertThat(customers.listAddresses(customer.id())).containsExactly(added);
    }

    @Test
    void newDefaultAddressReplacesPreviousDefaultWithoutUniqueViolation() {
        var customer =
                customers.create(
                        new CreateCustomerRequest(
                                "default-add@example.com",
                                "Default",
                                "Add",
                                null,
                                null,
                                List.of(address("SHIPPING", "Original", true)),
                                null));
        var added = customers.addAddress(customer.id(), address("SHIPPING", "Replacement", true));
        assertThat(added.id()).isNotNull();
        assertThat(customers.listAddresses(customer.id()).stream().filter(AddressDto::isDefault))
                .extracting(AddressDto::id)
                .containsExactly(added.id());
    }

    @Test
    void existingDefaultsCanSwitchBothWaysAndBeClearedOrDeleted() {
        var customer =
                customers.create(
                        new CreateCustomerRequest(
                                "default-update@example.com",
                                "Default",
                                "Update",
                                null,
                                null,
                                List.of(
                                        address("SHIPPING", "First", true),
                                        address("SHIPPING", "Second", false)),
                                null));
        UUID first = customer.addresses().get(0).id(), second = customer.addresses().get(1).id();
        for (UUID selected : List.of(second, first, second)) {
            customers.updateAddress(customer.id(), selected, address("SHIPPING", "Updated", true));
            assertThat(
                            customers.listAddresses(customer.id()).stream()
                                    .filter(AddressDto::isDefault))
                    .extracting(AddressDto::id)
                    .containsExactly(selected);
        }
        customers.updateAddress(
                customer.id(),
                second,
                new CreateAddressRequest(
                        "BILLING", "Moved", "Unit 2", "Berlin", null, "10115", "DE", false));
        var moved =
                customers.listAddresses(customer.id()).stream()
                        .filter(a -> a.id().equals(second))
                        .findFirst()
                        .orElseThrow();
        assertThat(moved.isDefault()).isFalse();
        assertThat(moved.country()).isEqualTo("DE");
        assertThat(moved.line2()).isEqualTo("Unit 2");
        customers.removeAddress(customer.id(), second);
        assertThat(customers.listAddresses(customer.id()))
                .extracting(AddressDto::id)
                .containsExactly(first);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from customers.address where id = ?",
                                Integer.class,
                                second))
                .isZero();
    }

    @Test
    void addressWritesCannotCrossCustomerBoundary() {
        var owner =
                customers.create(
                        new CreateCustomerRequest(
                                "address-owner@example.com",
                                "Owner",
                                "Customer",
                                null,
                                null,
                                List.of(address("BILLING", "Owned", true)),
                                null));
        var other = create("address-other");
        UUID id = owner.addresses().get(0).id();
        assertThatThrownBy(
                        () ->
                                customers.updateAddress(
                                        other.id(), id, address("BILLING", "Wrong", true)))
                .isInstanceOf(AddressNotFoundException.class);
        assertThatThrownBy(() -> customers.removeAddress(other.id(), id))
                .isInstanceOf(AddressNotFoundException.class);
        assertThat(customers.listAddresses(owner.id()).get(0).line1()).isEqualTo("Owned");
    }

    @Test
    void identityLinkIsIdempotentAndRejectsBothKindsOfConflict() {
        var first = create("link-first");
        var second = create("link-second");
        customers.linkKeycloakSub(first.id(), "lifecycle-user");
        customers.linkKeycloakSub(first.id(), "lifecycle-user");
        assertThat(customers.getByKeycloakSub("lifecycle-user").id()).isEqualTo(first.id());
        assertThatThrownBy(() -> customers.linkKeycloakSub(first.id(), "another-user"))
                .isInstanceOf(InvalidCustomerDataException.class);
        assertThatThrownBy(() -> customers.linkKeycloakSub(second.id(), "lifecycle-user"))
                .isInstanceOf(InvalidCustomerDataException.class);
        assertThat(customers.getById(second.id()).keycloakSub()).isNull();
    }

    @Test
    void combinedFiltersMatchEmbeddedEmailAndUnknownStatusMatchesNothing() {
        var customer = create("filter-match");
        var filter = new CustomerFilter("active", "LIFECYCLE", "EXAMPLE.COM");
        assertThat(customers.list(filter, PageRequest.of(0, 100)).content())
                .extracting(CustomerDto::id)
                .contains(customer.id());
        assertThat(
                        customers
                                .list(
                                        new CustomerFilter("unknown", null, null),
                                        PageRequest.of(0, 10))
                                .content())
                .isEmpty();
    }

    @Test
    void concurrentDefaultChangesSerializeAndLeaveOneDefault() throws Exception {
        var customer = create("default-race");
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first =
                    pool.submit(
                            () -> {
                                start.await();
                                return customers.addAddress(
                                        customer.id(), address("SHIPPING", "First", true));
                            });
            var second =
                    pool.submit(
                            () -> {
                                start.await();
                                return customers.addAddress(
                                        customer.id(), address("SHIPPING", "Second", true));
                            });
            start.countDown();
            assertThat(first.get(30, TimeUnit.SECONDS).id()).isNotNull();
            assertThat(second.get(30, TimeUnit.SECONDS).id()).isNotNull();
        }
        var stored = customers.listAddresses(customer.id());
        assertThat(stored).hasSize(2);
        assertThat(stored.stream().filter(AddressDto::isDefault)).hasSize(1);
    }

    @Test
    void failedDefaultPromotionRollsBackItsDemotion() {
        var customer =
                customers.create(
                        new CreateCustomerRequest(
                                "default-rollback@example.com",
                                "Default",
                                "Rollback",
                                null,
                                null,
                                List.of(address("SHIPPING", "Original", true)),
                                null));
        var invalid =
                new CreateAddressRequest(
                        "SHIPPING", "Invalid", null, "Portland", "OR", "97201", "us", true);
        assertThatThrownBy(() -> customers.addAddress(customer.id(), invalid))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(customers.listAddresses(customer.id()))
                .hasSize(1)
                .allSatisfy(
                        a -> {
                            assertThat(a.line1()).isEqualTo("Original");
                            assertThat(a.isDefault()).isTrue();
                        });
    }

    @Test
    void profileAndAddressMutationEndpointsReturnCommittedResources() {
        var customer = create("http-lifecycle");
        var auth = "Bearer " + TestSecurityConfig.adminToken();
        given().header("Authorization", auth)
                .contentType("application/json")
                .body(
                        new UpdateCustomerRequest(
                                "http-updated@example.com", "HTTP", "Updated", null, null))
                .put("/api/v1/customers/" + customer.id())
                .then()
                .statusCode(200)
                .body("email", equalTo("http-updated@example.com"));
        String addressId =
                given().header("Authorization", auth)
                        .contentType("application/json")
                        .body(address("SHIPPING", "HTTP Address", true))
                        .post("/api/v1/customers/" + customer.id() + "/addresses")
                        .then()
                        .statusCode(201)
                        .header("Location", not(endsWith("/null")))
                        .body("id", notNullValue())
                        .extract()
                        .path("id");
        given().header("Authorization", auth)
                .contentType("application/json")
                .body(address("BILLING", "HTTP Replacement", false))
                .put("/api/v1/customers/" + customer.id() + "/addresses/" + addressId)
                .then()
                .statusCode(200)
                .body("line1", equalTo("HTTP Replacement"))
                .body("isDefault", equalTo(false));
        given().header("Authorization", auth)
                .delete("/api/v1/customers/" + customer.id() + "/addresses/" + addressId)
                .then()
                .statusCode(204);
        given().header("Authorization", auth)
                .get("/api/v1/customers/" + customer.id() + "/addresses")
                .then()
                .statusCode(200)
                .body("$", hasSize(0));
        given().header("Authorization", auth)
                .post("/api/v1/customers/" + customer.id() + "/link-keycloak")
                .then()
                .statusCode(200)
                .body("keycloakSub", equalTo("test-admin"));
        given().header("Authorization", auth)
                .get("/api/v1/customers/me")
                .then()
                .statusCode(200)
                .body("id", equalTo(customer.id().toString()));
    }

    @Test
    void ownedRecordsKeepTheirIdentityAndForeignKeysAcrossLoads() {
        var customer =
                customers.create(
                        new CreateCustomerRequest(
                                "owned-records@example.com",
                                "Owned",
                                "Records",
                                null,
                                null,
                                List.of(address("BILLING", "Owned", true)),
                                new CreateCustomerRequest.CreatePreferencesRequest(
                                        null, null, null, null, null)));
        var tx = new TransactionTemplate(transactions);
        var detached =
                tx.execute(status -> repository.findByIdWithDetails(customer.id()).orElseThrow());
        tx.executeWithoutResult(
                status -> {
                    var loaded = repository.findByIdWithDetails(customer.id()).orElseThrow();
                    assertThat(new java.util.HashSet<>(List.of(loaded))).contains(detached);
                    assertThat(new java.util.HashSet<>(loaded.getAddresses()))
                            .contains(detached.getAddresses().getFirst());
                    var prefs = loaded.getPreferences();
                    assertThat(new java.util.HashSet<>(List.of(prefs)))
                            .contains(detached.getPreferences());
                    assertThat(prefs.getCustomerId()).isEqualTo(customer.id());
                    assertThat(prefs.getCustomer().getId()).isEqualTo(customer.id());
                    assertThat(prefs.getUpdatedAt()).isNotNull();
                    assertThat(loaded.getAddresses().getFirst().getCustomer().getId())
                            .isEqualTo(customer.id());
                    assertThat(loaded.getAddresses().getFirst().getCreatedAt()).isNotNull();
                    assertThat(loaded.getAddresses().getFirst().getUpdatedAt()).isNotNull();
                });
    }

    @Test
    void movingDefaultToAnotherTypeWithOmittedFlagPreservesItsStatus() {
        var customer =
                customers.create(
                        new CreateCustomerRequest(
                                "default-move@example.com",
                                "Default",
                                "Move",
                                null,
                                null,
                                List.of(
                                        address("SHIPPING", "Shipping", true),
                                        address("BILLING", "Billing", true)),
                                null));
        UUID shipping = customer.addresses().get(0).id();
        customers.updateAddress(
                customer.id(),
                shipping,
                new CreateAddressRequest(
                        "BILLING", "Moved", null, "Portland", "OR", "97201", "US", null));
        assertThat(customers.listAddresses(customer.id()).stream().filter(AddressDto::isDefault))
                .extracting(AddressDto::id)
                .containsExactly(shipping);
    }

    @Test
    void customerRoleCannotWriteAddressesEvenForOwnProfile() {
        var auth = "Bearer " + TestSecurityConfig.linkedAliceToken();
        String path = "/api/v1/customers/00000000-0000-0000-0000-000000000101/addresses";
        given().header("Authorization", auth)
                .contentType("application/json")
                .body(address("SHIPPING", "Unauthorized", true))
                .post(path)
                .then()
                .statusCode(403);
        given().header("Authorization", auth)
                .contentType("application/json")
                .body(address("SHIPPING", "Unauthorized", true))
                .put(path + "/" + UUID.randomUUID())
                .then()
                .statusCode(403);
        given().header("Authorization", auth)
                .delete(path + "/" + UUID.randomUUID())
                .then()
                .statusCode(403);
        given().header("Authorization", auth)
                .get(path)
                .then()
                .statusCode(200)
                .body("$", hasSize(2));
    }

    @Test
    void missingProfilesAndAddressesReturnDomainErrors() {
        UUID missing = UUID.randomUUID();
        assertThatThrownBy(() -> customers.getByEmail("absent@example.com"))
                .isInstanceOf(CustomerNotFoundException.class);
        assertThatThrownBy(
                        () ->
                                customers.update(
                                        missing,
                                        new UpdateCustomerRequest(
                                                "absent@example.com",
                                                "Missing",
                                                "Customer",
                                                null,
                                                null)))
                .isInstanceOf(CustomerNotFoundException.class);
        assertThatThrownBy(() -> customers.deactivate(missing))
                .isInstanceOf(CustomerNotFoundException.class);
        assertThatThrownBy(() -> customers.linkKeycloakSub(missing, "absent-user"))
                .isInstanceOf(CustomerNotFoundException.class);
        assertThatThrownBy(() -> customers.listAddresses(missing))
                .isInstanceOf(CustomerNotFoundException.class);
        assertThatThrownBy(
                        () -> customers.addAddress(missing, address("BILLING", "Missing", false)))
                .isInstanceOf(CustomerNotFoundException.class);
        assertThatThrownBy(
                        () ->
                                customers.updateAddress(
                                        missing,
                                        UUID.randomUUID(),
                                        address("BILLING", "Missing", false)))
                .isInstanceOf(CustomerNotFoundException.class);
        assertThatThrownBy(() -> customers.removeAddress(missing, UUID.randomUUID()))
                .isInstanceOf(CustomerNotFoundException.class);
        assertThatThrownBy(
                        () ->
                                customers.setPreferences(
                                        missing,
                                        new CustomerPreferencesDto(
                                                "en-US", "USD", false, true, false)))
                .isInstanceOf(CustomerNotFoundException.class);
    }

    private CustomerDto create(String name) {
        return customers.create(
                new CreateCustomerRequest(
                        name + "@example.com",
                        "Lifecycle",
                        "Customer",
                        null,
                        null,
                        List.of(),
                        null));
    }

    private CreateAddressRequest address(String type, String line, boolean selected) {
        return new CreateAddressRequest(
                type, line, null, "Portland", "OR", "97201", "US", selected);
    }
}
