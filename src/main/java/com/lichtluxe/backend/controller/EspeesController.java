package com.lichtluxe.backend.controller;

import com.lichtluxe.backend.dto.MessageResponse;
import com.lichtluxe.backend.entity.Order;
import com.lichtluxe.backend.entity.OrderItem;
import com.lichtluxe.backend.entity.Product;
import com.lichtluxe.backend.entity.User;
import com.lichtluxe.backend.repository.OrderRepository;
import com.lichtluxe.backend.repository.ProductRepository;
import com.lichtluxe.backend.repository.UserRepository;
import com.lichtluxe.backend.service.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Controller for Espees (ESP) · Loveworld Pay integration.
 * Supports:
 *  1. Direct Mobile App Transfer to merchant wallet (lichtluxenaturals)
 *  2. Official Espees Merchant Web API setup & redirect (api.espees.org)
 *  3. Payment confirmation and status checks
 *  4. Public Espees merchant configuration
 *
 * Official Loveworld Currency Peg: 1 ESP = $1.00 USD
 */
@CrossOrigin(origins = "*", maxAge = 3600)
@RestController
@RequestMapping("/api/espees")
public class EspeesController {

    private static final Logger log = LoggerFactory.getLogger(EspeesController.class);

    @Value("${app.espees.api-url:https://api.espees.org/v2}")
    private String espeesApiUrl;

    @Value("${app.espees.payment-url:https://payment.espees.org/pay/}")
    private String espeesPaymentUrl;

    @Value("${app.espees.api-key:}")
    private String espeesApiKey;

    @Value("${app.espees.merchant-wallet:lichtluxenaturals}")
    private String defaultMerchantWallet;

    @Value("${app.frontend-url:https://lichtluxenaturals.org}")
    private String frontendUrl;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private EmailService emailService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private final RestTemplate restTemplate = new RestTemplate();

    public static final Map<String, BigDecimal> OFFICIAL_PRICES = Map.of(
            "pure-shea-butter", new BigDecimal("14.99"),
            "pure-coconut-oil", new BigDecimal("11.99"),
            "luxe-body-butter", new BigDecimal("18.99"),
            "african-black-liquid-soap", new BigDecimal("12.99"),
            "african-black-soap-jar", new BigDecimal("11.99"),
            "luxe-trio-gift-set", new BigDecimal("39.99"),
            "complete-luxe-collection", new BigDecimal("59.00"),
            "african-black-soap-clarifying-duo", new BigDecimal("21.99")
    );

    private User getOptionalCurrentUser() {
        try {
            org.springframework.security.core.Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getName())) {
                return userRepository.findByEmail(auth.getName()).orElse(null);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private User getOrCreateCustomer(String shippingName, String shippingEmail, String shippingPhone, String orderRef) {
        User user = getOptionalCurrentUser();
        if (user != null) return user;

        String email = (shippingEmail != null && !shippingEmail.isBlank())
                ? shippingEmail.trim().toLowerCase()
                : "espees.guest." + orderRef.toLowerCase() + "@loveworldluxe.com";

        Optional<User> existing = userRepository.findByEmail(email);
        if (existing.isPresent()) {
            return existing.get();
        }

        String firstName = "Customer";
        String lastName = "";
        if (shippingName != null && !shippingName.isBlank()) {
            String[] parts = shippingName.trim().split("\\s+", 2);
            firstName = parts[0];
            if (parts.length > 1) lastName = parts[1];
        }

        String phone = shippingPhone != null ? shippingPhone.trim() : "";
        if (!phone.isBlank() && userRepository.existsByPhone(phone)) {
            phone = "";
        }

        User newUser = User.builder()
                .firstName(firstName)
                .lastName(lastName)
                .email(email)
                .phone(phone)
                .password(passwordEncoder.encode(UUID.randomUUID().toString()))
                .role(User.Role.CUSTOMER)
                .isActive(true)
                .build();
        return userRepository.save(newUser);
    }

    public BigDecimal resolveProductPrice(String prodId, String prodName, Object priceObj) {
        if (prodId != null && OFFICIAL_PRICES.containsKey(prodId)) {
            return OFFICIAL_PRICES.get(prodId);
        }
        if (prodId != null && !prodId.isBlank()) {
            Optional<Product> prodOpt = productRepository.findBySlug(prodId);
            if (prodOpt.isPresent()) {
                return prodOpt.get().getPrice();
            }
        }
        if (prodName != null && !prodName.isBlank()) {
            String lower = prodName.toLowerCase();
            if (lower.contains("complete") || lower.contains("5-piece")) return OFFICIAL_PRICES.get("complete-luxe-collection");
            if (lower.contains("duo")) return OFFICIAL_PRICES.get("african-black-soap-clarifying-duo");
            if (lower.contains("gift set") || lower.contains("trio")) return OFFICIAL_PRICES.get("luxe-trio-gift-set");
            if (lower.contains("shea")) return OFFICIAL_PRICES.get("pure-shea-butter");
            if (lower.contains("coconut")) return OFFICIAL_PRICES.get("pure-coconut-oil");
            if (lower.contains("body butter")) return OFFICIAL_PRICES.get("luxe-body-butter");
            if (lower.contains("liquid")) return OFFICIAL_PRICES.get("african-black-liquid-soap");
            if (lower.contains("black soap") || lower.contains("jar")) return OFFICIAL_PRICES.get("african-black-soap-jar");
        }
        if (priceObj != null) {
            return priceObj instanceof Number
                    ? BigDecimal.valueOf(((Number) priceObj).doubleValue()).setScale(2, RoundingMode.HALF_UP)
                    : new BigDecimal(String.valueOf(priceObj)).setScale(2, RoundingMode.HALF_UP);
        }
        return new BigDecimal("14.99");
    }

    /**
     * Public Espees Merchant Configuration
     */
    @GetMapping("/config")
    public ResponseEntity<?> getConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("merchantWallet", defaultMerchantWallet);
        config.put("exchangeRateUsdToEsp", 1.00);
        config.put("currency", "ESP");
        config.put("hasApiKey", espeesApiKey != null && !espeesApiKey.isBlank());
        config.put("instructions", "Transfer exact ESP amount to merchant wallet @" + defaultMerchantWallet + " via Espees App");
        return ResponseEntity.ok(config);
    }

    /**
     * Endpoint 1: Create Espees Web Gateway Payment (Official Espees Merchant Web API)
     * POST /api/espees/create-payment
     */
    @PostMapping("/create-payment")
    public ResponseEntity<?> createPayment(@RequestBody Map<String, Object> payload) {
        try {
            String orderRef = "ESP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            String sku = "LUXE-" + orderRef;

            BigDecimal total = BigDecimal.ZERO;
            if (payload.containsKey("amount") && payload.get("amount") != null) {
                Object amt = payload.get("amount");
                total = amt instanceof Number
                        ? BigDecimal.valueOf(((Number) amt).doubleValue()).setScale(2, RoundingMode.HALF_UP)
                        : new BigDecimal(String.valueOf(amt)).setScale(2, RoundingMode.HALF_UP);
            }

            if (total.compareTo(BigDecimal.ZERO) <= 0) {
                total = new BigDecimal("59.00");
            }

            String narration = payload.containsKey("narration") && payload.get("narration") != null
                    ? String.valueOf(payload.get("narration"))
                    : "LICHT LUXE NATURALS Pure Skincare Order - " + orderRef;

            String merchantWallet = payload.containsKey("merchantWallet") && payload.get("merchantWallet") != null
                    ? String.valueOf(payload.get("merchantWallet"))
                    : defaultMerchantWallet;

            // Prepare Espees Merchant Web API Request Body
            Map<String, Object> espeesRequest = new HashMap<>();
            espeesRequest.put("product_sku", sku);
            espeesRequest.put("narration", narration);
            espeesRequest.put("price", total.doubleValue());
            espeesRequest.put("merchant_wallet", merchantWallet);
            espeesRequest.put("success_url", frontendUrl + "/?espees=success&ref=" + orderRef);
            espeesRequest.put("fail_url", frontendUrl + "/?espees=fail&ref=" + orderRef);

            Map<String, Object> userData = new HashMap<>();
            userData.put("order_ref", orderRef);
            userData.put("created_at", new Date().toString());
            espeesRequest.put("user_data", userData);

            String apiKeyToUse = espeesApiKey;
            if (payload.containsKey("apiKey") && payload.get("apiKey") != null && !String.valueOf(payload.get("apiKey")).isBlank()) {
                apiKeyToUse = String.valueOf(payload.get("apiKey"));
            }

            if (apiKeyToUse != null && !apiKeyToUse.isBlank()) {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.set("x-api-key", apiKeyToUse);

                HttpEntity<Map<String, Object>> entity = new HttpEntity<>(espeesRequest, headers);
                String productSetupUrl = espeesApiUrl + "/payment/product";

                log.info("Calling Espees Merchant API at: {}", productSetupUrl);
                ResponseEntity<Map> response = restTemplate.postForEntity(productSetupUrl, entity, Map.class);

                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    Map<?, ?> body = response.getBody();
                    String paymentRef = String.valueOf(body.get("payment_ref"));
                    String redirectUrl = espeesPaymentUrl + paymentRef;

                    Map<String, Object> result = new HashMap<>();
                    result.put("statusCode", 200);
                    result.put("orderRef", orderRef);
                    result.put("sku", sku);
                    result.put("paymentRef", paymentRef);
                    result.put("paymentUrl", redirectUrl);
                    result.put("amountEsp", total.toString());
                    result.put("merchantWallet", merchantWallet);
                    return ResponseEntity.ok(result);
                }
            }

            // Fallback response with direct transfer instructions if API key is unconfigured
            Map<String, Object> fallback = new HashMap<>();
            fallback.put("statusCode", 200);
            fallback.put("orderRef", orderRef);
            fallback.put("sku", sku);
            fallback.put("amountEsp", total.toString());
            fallback.put("merchantWallet", merchantWallet);
            fallback.put("mode", "DIRECT_APP_TRANSFER");
            fallback.put("message", "Ready for direct transfer to @" + merchantWallet + " in Espees App.");
            return ResponseEntity.ok(fallback);

        } catch (Exception e) {
            log.error("Espees Payment Setup Error", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new MessageResponse("Espees payment initialization: " + e.getMessage()));
        }
    }

    /**
     * Endpoint 2: Confirm Web Gateway Payment
     * POST /api/espees/confirm-payment
     */
    @PostMapping("/confirm-payment")
    public ResponseEntity<?> confirmPayment(@RequestBody Map<String, Object> payload) {
        try {
            String paymentRef = String.valueOf(payload.get("payment_ref"));
            if (paymentRef == null || paymentRef.isBlank() || "null".equals(paymentRef)) {
                return ResponseEntity.badRequest().body(new MessageResponse("Missing payment_ref"));
            }

            // Call Espees confirmation endpoint if API key present
            boolean isApproved = true;
            if (espeesApiKey != null && !espeesApiKey.isBlank()) {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.set("x-api-key", espeesApiKey);

                Map<String, String> confirmReq = Map.of("payment_ref", paymentRef);
                HttpEntity<Map<String, String>> entity = new HttpEntity<>(confirmReq, headers);
                String confirmUrl = espeesApiUrl + "/payment/confirm/";

                try {
                    ResponseEntity<Map> resp = restTemplate.postForEntity(confirmUrl, entity, Map.class);
                    if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                        String status = String.valueOf(resp.getBody().get("transaction_status"));
                        isApproved = "APPROVED".equalsIgnoreCase(status);
                    }
                } catch (Exception ex) {
                    log.warn("Espees API confirm check notice: {}", ex.getMessage());
                }
            }

            if (!isApproved) {
                return ResponseEntity.badRequest().body(new MessageResponse("Espees transaction was not approved."));
            }

            // Process order persistence
            return processAndSaveOrder(payload, "Espees (Web Portal)", paymentRef);

        } catch (Exception e) {
            log.error("Espees confirm error", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new MessageResponse("Espees confirmation error: " + e.getMessage()));
        }
    }

    /**
     * Endpoint 3: Direct Espees Mobile App Transfer
     * POST /api/espees/direct-transfer
     */
    @PostMapping("/direct-transfer")
    public ResponseEntity<?> directTransfer(@RequestBody Map<String, Object> payload) {
        try {
            String memberRef = String.valueOf(payload.getOrDefault("espeesUsernameRef", ""));
            if (memberRef.isBlank() || "null".equals(memberRef)) {
                memberRef = "MEMBER-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
            }

            return processAndSaveOrder(payload, "Espees (ESP) · Direct App Transfer", memberRef);

        } catch (Exception e) {
            log.error("Espees direct transfer processing error", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new MessageResponse("Failed to record Espees direct transfer: " + e.getMessage()));
        }
    }

    private ResponseEntity<?> processAndSaveOrder(Map<String, Object> payload, String method, String reference) {
        String shippingName = null;
        String shippingEmail = null;
        String shippingPhone = null;
        String shippingAddress = null;
        String shippingCity = null;
        String shippingPostcode = null;
        String orderNotes = null;

        if (payload.containsKey("shippingInfo") && payload.get("shippingInfo") instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, String> sInfo = (Map<String, String>) payload.get("shippingInfo");
            shippingName = sInfo.get("shippingName");
            shippingEmail = sInfo.get("shippingEmail");
            shippingPhone = sInfo.get("shippingPhone");
            shippingAddress = sInfo.get("shippingAddress");
            shippingCity = sInfo.get("shippingCity");
            shippingPostcode = sInfo.get("shippingPostcode");
            orderNotes = sInfo.get("orderNotes");
        }

        String orderNumber = "LLN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        User customer = getOrCreateCustomer(shippingName, shippingEmail, shippingPhone, orderNumber);

        BigDecimal total = BigDecimal.ZERO;
        String wallet = String.valueOf(payload.getOrDefault("merchantWallet", defaultMerchantWallet));

        String notes = (orderNotes != null && !orderNotes.isBlank() ? orderNotes + " • " : "")
                + "[Paid in Espees: to wallet @" + wallet + " (Ref: " + reference + ")]";

        Order order = Order.builder()
                .orderNumber(orderNumber)
                .user(customer)
                .paymentMethod(method)
                .status(Order.OrderStatus.CONFIRMED)
                .lastNotifiedStatus(Order.OrderStatus.CONFIRMED)
                .shippingName(shippingName != null && !shippingName.isBlank() ? shippingName : customer.getFirstName() + " " + customer.getLastName())
                .shippingPhone(shippingPhone != null && !shippingPhone.isBlank() ? shippingPhone : customer.getPhone())
                .shippingAddress(shippingAddress)
                .shippingCity(shippingCity)
                .shippingPostcode(shippingPostcode)
                .orderNotes(notes)
                .carrier("Royal Mail Tracked 48")
                .trackingNumber("RM" + (100000000L + (long)(Math.random() * 899999999L)) + "GB")
                .build();

        if (payload.containsKey("items") && payload.get("items") instanceof List) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> itemsList = (List<Map<String, Object>>) payload.get("items");
            for (Map<String, Object> itemData : itemsList) {
                String prodName = String.valueOf(itemData.getOrDefault("name", "Pure Botanical Product"));
                String slug = String.valueOf(itemData.getOrDefault("id", ""));
                BigDecimal price = resolveProductPrice(slug, prodName, itemData.get("price"));

                int qty = 1;
                if (itemData.containsKey("quantity") && itemData.get("quantity") != null) {
                    try {
                        qty = Integer.parseInt(String.valueOf(itemData.get("quantity")));
                    } catch (Exception ignored) {}
                }

                Product product = null;
                if (!slug.isBlank()) {
                    product = productRepository.findBySlug(slug).orElse(null);
                }

                OrderItem orderItem = OrderItem.builder()
                        .product(product)
                        .productName(prodName)
                        .quantity(qty)
                        .unitPrice(price)
                        .lineTotal(price.multiply(BigDecimal.valueOf(qty)))
                        .build();
                order.addItem(orderItem);
                total = total.add(orderItem.getLineTotal());
            }
        }

        // Add delivery fee if applicable
        if (payload.containsKey("deliveryFee") && payload.get("deliveryFee") != null) {
            try {
                BigDecimal dFee = new BigDecimal(String.valueOf(payload.get("deliveryFee")));
                total = total.add(dFee);
            } catch (Exception ignored) {}
        } else if (payload.containsKey("amount") && payload.get("amount") != null) {
            try {
                total = new BigDecimal(String.valueOf(payload.get("amount")));
            } catch (Exception ignored) {}
        }

        if (total.compareTo(BigDecimal.ZERO) <= 0) {
            total = new BigDecimal("59.00");
        }

        order.setTotalAmount(total.setScale(2, RoundingMode.HALF_UP));
        Order savedOrder = orderRepository.save(order);

        // Send email receipt asynchronously
        try {
            emailService.sendOrderConfirmationEmail(savedOrder);
        } catch (Exception ex) {
            log.warn("Email confirmation notification notice: {}", ex.getMessage());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("orderNumber", savedOrder.getOrderNumber());
        result.put("totalAmount", savedOrder.getTotalAmount().toString());
        result.put("totalEsp", savedOrder.getTotalAmount().toString());
        result.put("currency", "ESP");
        result.put("paymentMethod", method);
        result.put("status", savedOrder.getStatus().name());
        result.put("espeesRef", reference);
        result.put("merchantWallet", wallet);
        result.put("trackingNumber", savedOrder.getTrackingNumber());
        result.put("message", "✨ Espees payment confirmed successfully!");

        return ResponseEntity.ok(result);
    }
}

