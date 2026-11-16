package com.lichtluxe.backend.service;

import com.lichtluxe.backend.dto.CartItemRequest;
import com.lichtluxe.backend.entity.Product;
import com.lichtluxe.backend.repository.ProductRepository;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Authoritative shared pricing and shipping calculation service.
 * Used identically by both OrderController (checkout) and PayPalController (order creation & capture)
 * ensuring client-side price tampering is completely impossible and totals can never diverge.
 */
@Service
public class PricingService {

    @Autowired
    private ProductRepository productRepository;

    @Value("${app.currency:GBP}")
    private String currency;

    @Value("${app.shipping.standard-fee:6.99}")
    private BigDecimal standardShippingFee;

    @Value("${app.shipping.express-fee:10.99}")
    private BigDecimal expressShippingFee;

    @Value("${app.shipping.free-threshold:70.00}")
    private BigDecimal freeShippingThreshold;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PricedItem {
        private Product product;
        private String productIdOrSlug;
        private String productName;
        private int quantity;
        private BigDecimal unitPrice;
        private BigDecimal lineTotal;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PricingResult {
        private BigDecimal subtotal;
        private BigDecimal deliveryFee;
        private BigDecimal total;
        private String currency;
        private String courier;
        private boolean isFreeShipping;
        private int totalQuantity;
        private List<PricedItem> items;
    }

    /**
     * Resolves canonical product entity by slug, numeric ID, or exact name.
     */
    public Product resolveProduct(String idOrSlug) {
        if (idOrSlug == null || idOrSlug.isBlank()) {
            throw new IllegalArgumentException("Product identifier cannot be empty.");
        }

        String clean = idOrSlug.trim();
        Optional<Product> prodOpt = productRepository.findBySlug(clean);
        if (prodOpt.isEmpty()) {
            try {
                Long numericId = Long.parseLong(clean);
                prodOpt = productRepository.findById(numericId);
            } catch (NumberFormatException ignored) {}
        }
        if (prodOpt.isEmpty()) {
            prodOpt = productRepository.findByName(clean);
        }

        return prodOpt.orElseThrow(() -> new IllegalArgumentException("Product not found: " + clean));
    }

    /**
     * Authoritative calculation from list of items with strict quantity checks and canonical DB prices.
     */
    public PricingResult calculatePricing(List<Map<String, Object>> itemsList, String deliveryOption) {
        if (itemsList == null || itemsList.isEmpty()) {
            throw new IllegalArgumentException("Cart must contain at least one item.");
        }

        // 1. Parse and validate quantities, merging duplicate item identifiers
        Map<String, Integer> itemQuantities = new LinkedHashMap<>();
        for (Map<String, Object> itemData : itemsList) {
            String prodIdOrSlug = null;
            if (itemData.containsKey("id") && itemData.get("id") != null) {
                prodIdOrSlug = String.valueOf(itemData.get("id")).trim();
            } else if (itemData.containsKey("slug") && itemData.get("slug") != null) {
                prodIdOrSlug = String.valueOf(itemData.get("slug")).trim();
            } else if (itemData.containsKey("productId") && itemData.get("productId") != null) {
                prodIdOrSlug = String.valueOf(itemData.get("productId")).trim();
            } else if (itemData.containsKey("name") && itemData.get("name") != null) {
                prodIdOrSlug = String.valueOf(itemData.get("name")).trim();
            }

            if (prodIdOrSlug == null || prodIdOrSlug.isBlank()) {
                throw new IllegalArgumentException("Product identifier (id or slug) is missing for an item.");
            }

            Object qtyObj = itemData.get("quantity");
            if (qtyObj == null) qtyObj = itemData.get("qty");
            if (qtyObj == null) {
                throw new IllegalArgumentException("Quantity is required for item: " + prodIdOrSlug);
            }

            int qty;
            try {
                if (qtyObj instanceof Number) {
                    qty = ((Number) qtyObj).intValue();
                } else {
                    qty = Integer.parseInt(String.valueOf(qtyObj).trim());
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid quantity format for item '" + prodIdOrSlug + "'. Quantity must be a valid integer.");
            }

            if (qty <= 0) {
                throw new IllegalArgumentException("Quantity for item '" + prodIdOrSlug + "' must be greater than zero.");
            }
            if (qty > 20) {
                throw new IllegalArgumentException("Quantity for item '" + prodIdOrSlug + "' cannot exceed maximum limit of 20 units.");
            }

            int currentMerged = itemQuantities.getOrDefault(prodIdOrSlug, 0);
            int newMerged = currentMerged + qty;
            if (newMerged > 20) {
                throw new IllegalArgumentException("Total combined quantity for item '" + prodIdOrSlug + "' cannot exceed 20 units.");
            }
            itemQuantities.put(prodIdOrSlug, newMerged);
        }

        // 2. Resolve products from database, enforce active status, calculate canonical prices
        BigDecimal subtotal = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        List<PricedItem> pricedItems = new ArrayList<>();
        int totalQuantity = 0;

        for (Map.Entry<String, Integer> entry : itemQuantities.entrySet()) {
            String identifier = entry.getKey();
            int qty = entry.getValue();

            Product product = resolveProduct(identifier);
            if (!product.isActive()) {
                throw new IllegalArgumentException("Product '" + product.getName() + "' is currently unavailable.");
            }

            BigDecimal unitPrice = product.getPrice().setScale(2, RoundingMode.HALF_UP);
            BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP);

            subtotal = subtotal.add(lineTotal);
            totalQuantity += qty;

            pricedItems.add(PricedItem.builder()
                    .product(product)
                    .productIdOrSlug(identifier)
                    .productName(product.getName())
                    .quantity(qty)
                    .unitPrice(unitPrice)
                    .lineTotal(lineTotal)
                    .build());
        }

        subtotal = subtotal.setScale(2, RoundingMode.HALF_UP);

        // 3. Delivery fee calculation based on configured rules
        String opt = (deliveryOption != null ? deliveryOption : "").toLowerCase();
        boolean isExpress = opt.contains("express") || opt.contains("special");

        BigDecimal deliveryFee;
        String courier;
        boolean isFreeShipping = false;

        if (isExpress) {
            deliveryFee = expressShippingFee.setScale(2, RoundingMode.HALF_UP);
            courier = "Royal Mail Special Delivery Guaranteed";
        } else {
            if (subtotal.compareTo(freeShippingThreshold) >= 0) {
                deliveryFee = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
                courier = "Royal Mail Tracked 48";
                isFreeShipping = true;
            } else {
                deliveryFee = standardShippingFee.setScale(2, RoundingMode.HALF_UP);
                courier = "Royal Mail Tracked 48";
            }
        }

        BigDecimal total = subtotal.add(deliveryFee).setScale(2, RoundingMode.HALF_UP);

        return PricingResult.builder()
                .subtotal(subtotal)
                .deliveryFee(deliveryFee)
                .total(total)
                .currency(currency)
                .courier(courier)
                .isFreeShipping(isFreeShipping)
                .totalQuantity(totalQuantity)
                .items(pricedItems)
                .build();
    }
}

