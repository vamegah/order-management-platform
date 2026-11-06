package com.example.omp.customer.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Per-customer preferences. 1:1 with {@link Customer}.
 *
 * <p>Kept in a separate table so the {@code customer} table stays narrow and hot. Preferences are
 * read on every order (currency, locale) but rarely updated.
 *
 * <p>Lifecycle is owned by {@link Customer}: saving the customer cascades to its preferences;
 * removing preferences from the customer deletes them.
 */
@Entity
@Table(name = "customer_preferences", schema = "customers")
@EntityListeners(AuditingEntityListener.class)
public class CustomerPreferences {

    /** Same value as the owning customer's id. Not independently generated. */
    @Id
    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    /** The owning customer. */
    @MapsId
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    /** BCP 47 language tag (e.g., "en-US", "de-DE"). */
    @NotBlank
    @Size(max = 20)
    @Column(name = "locale", nullable = false, length = 20)
    private String locale = "en-US";

    /** ISO 4217 currency code (e.g., "USD", "EUR"). */
    @NotBlank
    @Size(min = 3, max = 3)
    @Column(name = "currency", nullable = false, length = 3, columnDefinition = "bpchar(3)")
    private String currency = "USD";

    @Column(name = "marketing_opt_in", nullable = false)
    private boolean marketingOptIn = false;

    @Column(name = "notification_email", nullable = false)
    private boolean notificationEmail = true;

    @Column(name = "notification_sms", nullable = false)
    private boolean notificationSms = false;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    protected CustomerPreferences() {}

    public CustomerPreferences(String locale, String currency) {
        this.locale = locale;
        this.currency = currency;
    }

    /** Default preferences: en-US, USD, no marketing, email notifications on. */
    public static CustomerPreferences defaults() {
        return new CustomerPreferences("en-US", "USD");
    }

    // -------------------------------------------------------------------------
    // Getters / setters
    // -------------------------------------------------------------------------

    public UUID getCustomerId() {
        return customerId;
    }

    public Customer getCustomer() {
        return customer;
    }

    void setCustomer(Customer customer) {
        this.customer = customer;
    }

    public String getLocale() {
        return locale;
    }

    public void setLocale(String locale) {
        this.locale = locale;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public boolean isMarketingOptIn() {
        return marketingOptIn;
    }

    public void setMarketingOptIn(boolean marketingOptIn) {
        this.marketingOptIn = marketingOptIn;
    }

    public boolean isNotificationEmail() {
        return notificationEmail;
    }

    public void setNotificationEmail(boolean notificationEmail) {
        this.notificationEmail = notificationEmail;
    }

    public boolean isNotificationSms() {
        return notificationSms;
    }

    public void setNotificationSms(boolean notificationSms) {
        this.notificationSms = notificationSms;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CustomerPreferences other)) return false;
        return Objects.equals(customerId, other.customerId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(customerId);
    }
}
