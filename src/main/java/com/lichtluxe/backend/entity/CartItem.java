package com.lichtluxe.backend.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import lombok.*;

import java.math.BigDecimal;

/**
 * Individual line item within a shopping cart.
 */
@Entity
@Table(name = "cart_items")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class CartItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cart_id")
    private Cart cart;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Min(1)
    @Builder.Default
    private int quantity = 1;

    /**
     * Price at time of adding to cart (snapshot for consistency).
     */
    @Column(name = "unit_price")
    private BigDecimal unitPrice;
}
