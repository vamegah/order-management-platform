package com.example.omp.customer.domain;

import com.example.omp.shared.domain.Email;
import com.example.omp.shared.domain.PhoneNumber;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * A customer profile.
 *
 * <p>Business identity is {@link Email}. Case-insensitive unique. The optional {@code keycloakSub}
 * links this customer to an authenticated Keycloak user (JWT {@code sub} claim).
 *
 * <p>Deletion is soft: set {@link #status} to {@link CustomerStatus#INACTIVE}. Historical orders
 * reference customers by ID, so hard deletion would break referential integrity in future services.
 *
 * <p>Addresses and preferences are owned by the customer. Removing them from the collections or
 * deleting the customer cascades.
 */
@Entity
@Table(name = "customer", schema = "customers")
@EntityListeners(AuditingEntityListener.class)
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** Business identity. Case-insensitive unique. */
    @NotNull
    @Embedded
    @AttributeOverride(
            name = "value",
            column = @Column(name = "email", nullable = false, columnDefinition = "citext"))
    private Email email;

    /** Keycloak {@code sub} claim. Links this customer to a login. */
    @Column(name = "keycloak_sub", length = 255)
    private String keycloakSub;

    @NotBlank
    @Size(max = 100)
    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @NotBlank
    @Size(max = 100)
    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    /** Optional. Stored as provided (no normalization in Phase 1). */
    @Embedded private PhoneNumber phone;

    /** Optional date of birth. */
    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CustomerStatus status = CustomerStatus.ACTIVE;

    /** Addresses. Owned by the customer; deleting it deletes them. */
    @OneToMany(
            mappedBy = "customer",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY)
    private List<Address> addresses = new ArrayList<>();

    /** Preferences. 1:1, cascaded. May be null (customer without prefs). */
    @OneToOne(
            mappedBy = "customer",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY)
    private CustomerPreferences preferences;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    protected Customer() {}

    public Customer(Email email, String firstName, String lastName) {
        this.email = email;
        this.firstName = firstName;
        this.lastName = lastName;
        this.status = CustomerStatus.ACTIVE;
    }

    // -------------------------------------------------------------------------
    // Domain behavior
    // -------------------------------------------------------------------------

    /** Marks the customer as active. */
    public void activate() {
        this.status = CustomerStatus.ACTIVE;
    }

    /** Marks the customer as inactive (soft delete). */
    public void deactivate() {
        this.status = CustomerStatus.INACTIVE;
    }

    /** Suspends the customer. They exist but cannot place orders. */
    public void suspend() {
        this.status = CustomerStatus.SUSPENDED;
    }

    /** Links this customer to a Keycloak user. */
    public void linkKeycloakUser(String sub) {
        this.keycloakSub = sub;
    }

    /** Unlinks the customer from Keycloak. */
    public void unlinkKeycloakUser() {
        this.keycloakSub = null;
    }

    /** Adds an address. If it's flagged as default, unsets any prior default of the same type. */
    public void addAddress(Address address) {
        if (address.isDefault()) {
            unsetDefaultForType(address.getAddressType());
        }
        addresses.add(address);
        address.setCustomer(this);
    }

    /** Removes an address. */
    public void removeAddress(Address address) {
        addresses.remove(address);
        address.setCustomer(null);
    }

    /**
     * Marks an address as default for its type, unsetting any existing default of the same type.
     * The uniqueness is enforced by a partial unique index in the database; this method keeps the
     * entity graph consistent.
     */
    public void setDefaultAddress(Address address) {
        requireOwnedAddress(address);
        unsetDefaultForType(address.getAddressType());
        address.setDefault(true);
    }

    /** Clears the default flag without affecting other addresses of the same type. */
    public void clearDefaultAddress(Address address) {
        requireOwnedAddress(address);
        address.setDefault(false);
    }

    private void requireOwnedAddress(Address address) {
        if (!addresses.contains(address)) {
            throw new IllegalArgumentException("Address does not belong to this customer");
        }
    }

    /** Clears defaults for this type before a transactional replacement is persisted. */
    public void unsetDefaultForType(AddressType type) {
        addresses.stream()
                .filter(a -> a.getAddressType() == type && a.isDefault())
                .forEach(a -> a.setDefault(false));
    }

    /** Attaches preferences. Replaces any existing preferences. */
    public void setPreferences(CustomerPreferences prefs) {
        if (prefs != null) {
            prefs.setCustomer(this);
        }
        this.preferences = prefs;
    }

    // -------------------------------------------------------------------------
    // Getters / setters
    // -------------------------------------------------------------------------

    public UUID getId() {
        return id;
    }

    public Email getEmail() {
        return email;
    }

    public void setEmail(Email email) {
        this.email = email;
    }

    public String getKeycloakSub() {
        return keycloakSub;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public PhoneNumber getPhone() {
        return phone;
    }

    public void setPhone(PhoneNumber phone) {
        this.phone = phone;
    }

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public void setDateOfBirth(LocalDate dateOfBirth) {
        this.dateOfBirth = dateOfBirth;
    }

    public CustomerStatus getStatus() {
        return status;
    }

    public List<Address> getAddresses() {
        return addresses;
    }

    public CustomerPreferences getPreferences() {
        return preferences;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    // -------------------------------------------------------------------------
    // Equality
    // -------------------------------------------------------------------------

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Customer other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "Customer{id=" + id + ", email=" + email + ", status=" + status + "}";
    }
}
