package com.example.omp.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Objects;

/**
 * Shipping address snapshot for an order.
 *
 * <p>Copied from the customer's address at order time. Immutable thereafter — changes to the
 * customer's address in {@code customer-service} do not affect existing orders.
 *
 * <p>Embedded into {@link Order} as inline columns ({@code shipping_line1}, {@code shipping_city},
 * etc.).
 */
@Embeddable
public class ShippingAddress {

    @NotBlank
    @Size(max = 200)
    @Column(name = "shipping_line1", nullable = false, length = 200)
    private String line1;

    @Size(max = 200)
    @Column(name = "shipping_line2", length = 200)
    private String line2;

    @NotBlank
    @Size(max = 100)
    @Column(name = "shipping_city", nullable = false, length = 100)
    private String city;

    @Size(max = 100)
    @Column(name = "shipping_state", length = 100)
    private String state;

    @NotBlank
    @Size(max = 20)
    @Column(name = "shipping_postal_code", nullable = false, length = 20)
    private String postalCode;

    @NotBlank
    @Size(min = 2, max = 2)
    @Column(name = "shipping_country", nullable = false, length = 2, columnDefinition = "bpchar(2)")
    private String country;

    protected ShippingAddress() {}

    public ShippingAddress(
            String line1,
            String line2,
            String city,
            String state,
            String postalCode,
            String country) {
        this.line1 = line1;
        this.line2 = line2;
        this.city = city;
        this.state = state;
        this.postalCode = postalCode;
        this.country = country;
    }

    public String getLine1() {
        return line1;
    }

    public String getLine2() {
        return line2;
    }

    public String getCity() {
        return city;
    }

    public String getState() {
        return state;
    }

    public String getPostalCode() {
        return postalCode;
    }

    public String getCountry() {
        return country;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ShippingAddress other)) return false;
        return Objects.equals(line1, other.line1)
                && Objects.equals(line2, other.line2)
                && Objects.equals(city, other.city)
                && Objects.equals(state, other.state)
                && Objects.equals(postalCode, other.postalCode)
                && Objects.equals(country, other.country);
    }

    @Override
    public int hashCode() {
        return Objects.hash(line1, line2, city, state, postalCode, country);
    }

    @Override
    public String toString() {
        return line1 + ", " + city + " " + postalCode + ", " + country;
    }
}
