package com.lichtluxe.backend.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Product entity for the LICHT LUXE NATURALS collection.
 * Products: Pure Shea Butter, Pure Coconut Oil, Luxe Body Butter, Luxe Trio Gift Set
 */
@Entity
@Table(name = "products")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Size(max = 100)
    @Column(unique = true)
    private String slug;

    @NotBlank
    @Size(max = 200)
    private String name;

    @Size(max = 300)
    private String tagline;

    @Size(max = 200)
    private String subtitle;

    @NotNull
    @Positive
    private BigDecimal price;

    @Column(name = "original_price")
    private BigDecimal originalPrice;

    @Size(max = 100)
    private String badge;

    @Size(max = 100)
    private String volume;

    @Column(columnDefinition = "TEXT")
    private String shortDescription;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String howToUse;

    @Column(name = "image_url")
    private String imageUrl;

    @ElementCollection
    @CollectionTable(name = "product_gallery", joinColumns = @JoinColumn(name = "product_id"))
    @Column(name = "image_url")
    @Builder.Default
    private List<String> gallery = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "product_perfect_for", joinColumns = @JoinColumn(name = "product_id"))
    @Column(name = "usage_text")
    @Builder.Default
    private List<String> perfectFor = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    @Builder.Default
    private ProductCategory category = ProductCategory.BODY_CARE;

    @Column(name = "is_active")
    @Builder.Default
    private boolean isActive = true;

    @Column(name = "is_featured")
    @Builder.Default
    private boolean isFeatured = false;

    @Column(name = "stock_quantity")
    @Builder.Default
    private int stockQuantity = 100;

    @Column(name = "created_at", updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public enum ProductCategory {
        BODY_CARE,
        GIFT_SET,
        OIL,
        BUTTER
    }
}
