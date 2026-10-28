package com.lichtluxe.backend.config;

import com.lichtluxe.backend.entity.Product;
import com.lichtluxe.backend.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Initializes and updates the 6 official LICHT LUXE NATURALS products and authoritative prices in PostgreSQL.
 * If someone purchases all 6 products, the calculated total is exactly:
 * £14.99 + £11.99 + £18.99 + £12.99 + £11.99 + £39.99 = £110.94
 */
@Component
public class DatabaseSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseSeeder.class);

    @Autowired
    private ProductRepository productRepository;

    @Override
    public void run(String... args) {
        seedOrUpdateProduct(
                "pure-shea-butter",
                "Pure Shea Butter",
                "Pure nourishment from nature.",
                "Pure • Rich • Nourishing",
                new BigDecimal("14.99"),
                null,
                "100% RAW & UNREFINED",
                "250 g / 8.8 oz",
                "Rich, creamy and naturally nourishing, our pure shea butter is a simple skincare essential.",
                "Our pure shea butter is a natural skincare staple, traditionally valued for its moisturising properties.",
                "/shea_butter_primary.jpg",
                Product.ProductCategory.BUTTER
        );

        seedOrUpdateProduct(
                "pure-coconut-oil",
                "Pure Coconut Oil",
                "Nature's nourishing oil.",
                "Natural • Versatile • Nourishing",
                new BigDecimal("11.99"),
                null,
                "COLD PRESSED",
                "200 ml / 7.0 fl oz",
                "A versatile natural oil that helps moisturise, soften and nourish the skin.",
                "A beautiful multi-purpose natural oil suitable for everyday beauty and body-care routines.",
                "/coconut_oil_primary.jpg",
                Product.ProductCategory.OIL
        );

        seedOrUpdateProduct(
                "luxe-body-butter",
                "Luxe Body Butter",
                "Deep nourishment. Luxurious feel.",
                "Rich • Creamy • Luxurious",
                new BigDecimal("18.99"),
                null,
                "SIGNATURE FORMULA",
                "250 g / 8.8 oz",
                "Our rich body butter combines natural ingredients to give your skin a soft and moisturised feel.",
                "Give your skin a richer moisturising experience with our luxurious body butter.",
                "/body_butter_primary.jpg",
                Product.ProductCategory.BODY_CARE
        );

        seedOrUpdateProduct(
                "african-black-liquid-soap",
                "African Black Liquid Soap",
                "Deep cleansing. Skin & body.",
                "Deep Cleansing • Clarifying • 100% Botanical",
                new BigDecimal("12.99"),
                null,
                "100% NATURAL CLEANSE",
                "500 ml / 16.9 fl oz",
                "Our African Black Liquid Soap is a rich, natural cleanser crafted from traditional botanicals.",
                "Formulated with an amber pump dispenser, it gently cleanses and purifies the skin.",
                "/african_black_soap_primary.jpg",
                Product.ProductCategory.BODY_CARE
        );

        seedOrUpdateProduct(
                "african-black-soap-jar",
                "African Black Soap",
                "Deep cleansing • Purifying • Natural glow.",
                "Deep Cleansing • Purifying • Natural Glow (Face & Body)",
                new BigDecimal("11.99"),
                null,
                "TRADITIONAL 300G JAR",
                "300 g / 10.5 oz",
                "Traditional natural cleanser made from plantain skins, cocoa pod ash and natural botanicals.",
                "Hand-packed in a 300g jar, it gently cleanses, clears blemishes and leaves skin glowing.",
                "/african_black_soap_jar.jpg",
                Product.ProductCategory.BODY_CARE
        );

        seedOrUpdateProduct(
                "luxe-trio-gift-set",
                "The Luxe Trio Gift Set",
                "The complete natural luxury collection.",
                "Pure Shea Butter + Pure Coconut Oil + Luxe Body Butter",
                new BigDecimal("39.99"),
                new BigDecimal("45.97"),
                "MADE IN LOVEWORLD BOX",
                "Full Size 3-Piece Set",
                "Our signature gift box containing Pure Shea Butter, Pure Coconut Oil, and Luxe Body Butter.",
                "Give the gift of natural luxury with our complete 3-piece ritual box set.",
                "/luxe_trio_primary.jpg",
                Product.ProductCategory.GIFT_SET
        );

        log.info("LICHT LUXE NATURALS: Official products and prices synchronized in PostgreSQL.");
    }

    private void seedOrUpdateProduct(String slug, String name, String tagline, String subtitle,
                                     BigDecimal price, BigDecimal originalPrice, String badge,
                                     String volume, String shortDesc, String desc,
                                     String imageUrl, Product.ProductCategory category) {
        Product product = productRepository.findBySlug(slug).orElse(null);
        if (product == null) {
            product = Product.builder()
                    .slug(slug)
                    .name(name)
                    .tagline(tagline)
                    .subtitle(subtitle)
                    .price(price)
                    .originalPrice(originalPrice)
                    .badge(badge)
                    .volume(volume)
                    .shortDescription(shortDesc)
                    .description(desc)
                    .imageUrl(imageUrl)
                    .category(category)
                    .isActive(true)
                    .stockQuantity(100)
                    .build();
        } else {
            product.setName(name);
            product.setPrice(price);
            product.setOriginalPrice(originalPrice);
            product.setVolume(volume);
            product.setTagline(tagline);
            product.setSubtitle(subtitle);
            product.setImageUrl(imageUrl);
        }
        productRepository.save(product);
    }
}
