package com.example.omp.product.domain;

import com.example.omp.shared.domain.Money;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A product in the catalog. One row per SKU.
 *
 * <p>Maps to {@code products.product} (see V1__initial_schema.sql).
 *
 * <p>Deletion is soft: set {@link #status} to {@link ProductStatus#DISCONTINUED} rather than
 * removing the row. Historical orders reference products by ID.
 */
@Entity
@Table(name = "product", schema = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** Business identifier. Case-insensitive unique. */
    @NotNull @Embedded private Sku sku;

    @NotBlank
    @Size(max = 300)
    @Column(name = "name", nullable = false, length = 300)
    private String name;

    @Column(name = "description")
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    /** Price as a value object (amount + currency). Stored as two columns. */
    @NotNull
    @Embedded
    @AttributeOverrides({
        @AttributeOverride(
                name = "amount",
                column =
                        @Column(
                                name = "price_amount",
                                nullable = false,
                                precision = 19,
                                scale = 4)),
        @AttributeOverride(
                name = "currency",
                column =
                        @Column(
                                name = "price_currency",
                                nullable = false,
                                length = 3,
                                columnDefinition = "bpchar(3)"))
    })
    private Money price;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ProductStatus status = ProductStatus.ACTIVE;

    /** Images. Owned by the product; deleting the product deletes them. */
    @OneToMany(
            mappedBy = "product",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY)
    @OrderBy("sortOrder ASC")
    private List<ProductImage> images = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    @PrePersist
    void initializeTimestamps() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void updateTimestamp() {
        updatedAt = Instant.now();
    }

    protected Product() {
        // JPA only
    }

    public Product(Sku sku, String name, String description, Category category, Money price) {
        this.sku = sku;
        this.name = name;
        this.description = description;
        this.category = category;
        this.price = price;
        this.status = ProductStatus.ACTIVE;
    }

    // -------------------------------------------------------------------------
    // Domain behavior
    // -------------------------------------------------------------------------

    /** Adds an image to the product, maintaining both sides of the relationship. */
    public void addImage(ProductImage image) {
        images.add(image);
        image.setProduct(this);
    }

    /** Removes an image from the product. */
    public void removeImage(ProductImage image) {
        images.remove(image);
        image.setProduct(null);
    }

    /** Marks the product inactive. */
    public void deactivate() {
        this.status = ProductStatus.INACTIVE;
    }

    /** Marks the product discontinued. */
    public void discontinue() {
        this.status = ProductStatus.DISCONTINUED;
    }

    // -------------------------------------------------------------------------
    // Getters / setters
    // -------------------------------------------------------------------------

    public UUID getId() {
        return id;
    }

    public Sku getSku() {
        return sku;
    }

    public void setSku(Sku sku) {
        this.sku = sku;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Category getCategory() {
        return category;
    }

    public void setCategory(Category category) {
        this.category = category;
    }

    public Money getPrice() {
        return price;
    }

    public void setPrice(Money price) {
        this.price = price;
    }

    public ProductStatus getStatus() {
        return status;
    }

    public void setStatus(ProductStatus status) {
        this.status = status;
    }

    public List<ProductImage> getImages() {
        return images;
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
        if (!(o instanceof Product other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "Product{id=" + id + ", sku=" + sku + ", name='" + name + "'}";
    }
}
