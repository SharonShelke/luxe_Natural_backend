package com.lichtluxe.backend.controller;

import com.lichtluxe.backend.dto.MessageResponse;
import com.lichtluxe.backend.entity.Cart;
import com.lichtluxe.backend.entity.CartItem;
import com.lichtluxe.backend.entity.Order;
import com.lichtluxe.backend.entity.OrderItem;
import com.lichtluxe.backend.entity.Product;
import com.lichtluxe.backend.entity.User;
import com.lichtluxe.backend.repository.CartRepository;
import com.lichtluxe.backend.repository.OrderRepository;
import com.lichtluxe.backend.repository.ProductRepository;
import com.lichtluxe.backend.repository.UserRepository;
import com.lichtluxe.backend.service.EmailService;
import com.paypal.core.PayPalHttpClient;
import com.paypal.http.HttpResponse;
import com.paypal.orders.AmountBreakdown;
import com.paypal.orders.AmountWithBreakdown;
import com.paypal.orders.ApplicationContext;
import com.paypal.orders.Item;
import com.paypal.orders.Money;
import com.paypal.orders.OrdersCaptureRequest;
import com.paypal.orders.OrdersCreateRequest;
import com.paypal.orders.PurchaseUnitRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Controller for end-to-end PayPal checkout flows.
 * Supports both registered customers and guest checkout clients.
 * Every purchase and client is permanently recorded in the database and visible in the Admin Portal.
 */
@CrossOrigin(origins = "*", maxAge = 3600)
@RestController
@RequestMapping("/api/paypal")
public class PayPalController {

    @Autowired
    private PayPalHttpClient payPalHttpClient;

    @Autowired
    private CartRepository cartRepository;

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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PayPalController.class);

    public static final Map<String, BigDecimal> OFFICIAL_PRICES = Map.of(
            "pure-shea-butter", new BigDecimal("14.99"),
            "pure-coconut-oil", new BigDecimal("11.99"),
            "luxe-body-butter", new BigDecimal("18.99"),
            "african-black-liquid-soap", new BigDecimal("12.99"),
            "african-black-soap-jar", new BigDecimal("11.99"),
            "luxe-trio-gift-set", new BigDecimal("39.99")
    );

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
        return BigDecimal.ZERO;
    }

    @PostMapping("/calculate")
    public ResponseEntity<?> calculateOrderTotal(@RequestBody(required = false) Map<String, Object> payload) {
        BigDecimal subtotal = BigDecimal.ZERO;
        List<Map<String, Object>> itemBreakdown = new ArrayList<>();
        int totalQuantity = 0;

        if (payload != null && payload.containsKey("items") && payload.get("items") instanceof List) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> itemsList = (List<Map<String, Object>>) payload.get("items");
            for (Map<String, Object> itemData : itemsList) {
                String id = String.valueOf(itemData.getOrDefault("id", ""));
                String name = String.valueOf(itemData.getOrDefault("name", "Product"));
                int qty = 1;
                if (itemData.containsKey("quantity") && itemData.get("quantity") != null) {
                    try {
                        qty = Integer.parseInt(String.valueOf(itemData.get("quantity")));
                    } catch (Exception ignored) { qty = 1; }
                }
                if (qty <= 0) continue;

                BigDecimal unitPrice = resolveProductPrice(id, name, itemData.get("price"));
                BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP);
                subtotal = subtotal.add(lineTotal);
                totalQuantity += qty;

                Map<String, Object> line = new LinkedHashMap<>();
                line.put("id", id);
                line.put("name", name);
                line.put("quantity", qty);
                line.put("unitPrice", unitPrice);
                line.put("lineTotal", lineTotal);
                itemBreakdown.add(line);
            }
        }

        subtotal = subtotal.setScale(2, RoundingMode.HALF_UP);
        boolean isFreeShipping = subtotal.compareTo(new BigDecimal("50.00")) >= 0;
        BigDecimal shipping = (isFreeShipping || subtotal.compareTo(BigDecimal.ZERO) == 0)
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : new BigDecimal("4.99");
        BigDecimal total = subtotal.add(shipping).setScale(2, RoundingMode.HALF_UP);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("subtotal", subtotal);
        resp.put("shipping", shipping);
        resp.put("isFreeShipping", isFreeShipping);
        resp.put("total", total);
        resp.put("currency", "GBP");
        resp.put("itemCount", totalQuantity);
        resp.put("items", itemBreakdown);
        return ResponseEntity.ok(resp);
    }

    private User getOptionalCurrentUser() {
        org.springframework.security.core.Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            return null;
        }
        return userRepository.findByEmail(auth.getName()).orElse(null);
    }

    @PostMapping("/create-order")
    public ResponseEntity<?> createOrder(@RequestBody(required = false) Map<String, Object> payload) {
        try {
            User user = getOptionalCurrentUser();
            BigDecimal total = BigDecimal.ZERO;
            List<Item> paypalItems = new ArrayList<>();

            if (payload != null && payload.containsKey("items") && payload.get("items") instanceof List) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> itemsList = (List<Map<String, Object>>) payload.get("items");
                for (Map<String, Object> itemData : itemsList) {
                    String id = String.valueOf(itemData.getOrDefault("id", ""));
                    String name = String.valueOf(itemData.getOrDefault("name", "Product"));
                    BigDecimal price = resolveProductPrice(id, name, itemData.get("price"));

                    int qty = 1;
                    if (itemData.containsKey("quantity") && itemData.get("quantity") != null) {
                        Object qObj = itemData.get("quantity");
                        if (qObj instanceof Number) {
                            qty = ((Number) qObj).intValue();
                        } else {
                            try {
                                qty = Integer.parseInt(String.valueOf(qObj));
                            } catch (Exception ignored) {
                                qty = 1;
                            }
                        }
                    }

                    total = total.add(price.multiply(BigDecimal.valueOf(qty)));
                    paypalItems.add(new Item()
                            .name(name.length() > 120 ? name.substring(0, 117) + "..." : name)
                            .unitAmount(new Money().currencyCode("GBP").value(price.toString()))
                            .quantity(String.valueOf(qty))
                            .category("PHYSICAL_GOODS"));
                }
            } else if (payload != null && payload.containsKey("amount") && payload.get("amount") != null) {
                Object amt = payload.get("amount");
                total = amt instanceof Number
                        ? BigDecimal.valueOf(((Number) amt).doubleValue()).setScale(2, RoundingMode.HALF_UP)
                        : new BigDecimal(String.valueOf(amt)).setScale(2, RoundingMode.HALF_UP);
                paypalItems.add(new Item()
                        .name("LICHT LUXE NATURALS Order")
                        .unitAmount(new Money().currencyCode("GBP").value(total.toString()))
                        .quantity("1")
                        .category("PHYSICAL_GOODS"));
            } else if (user != null) {
                Optional<Cart> cartOpt = cartRepository.findByUser(user);
                if (cartOpt.isPresent() && !cartOpt.get().getItems().isEmpty()) {
                    Cart cart = cartOpt.get();
                    for (CartItem cartItem : cart.getItems()) {
                        BigDecimal itemPrice = cartItem.getUnitPrice().setScale(2, RoundingMode.HALF_UP);
                        total = total.add(itemPrice.multiply(BigDecimal.valueOf(cartItem.getQuantity())));
                        paypalItems.add(new Item()
                                .name(cartItem.getProduct().getName())
                                .unitAmount(new Money().currencyCode("GBP").value(itemPrice.toString()))
                                .quantity(String.valueOf(cartItem.getQuantity()))
                                .category("PHYSICAL_GOODS"));
                    }
                }
            }

            if (total.compareTo(BigDecimal.ZERO) <= 0) {
                return ResponseEntity.badRequest()
                        .body(new MessageResponse("Order amount must be greater than zero. Cart may be empty."));
            }

            total = total.setScale(2, RoundingMode.HALF_UP);

            com.paypal.orders.OrderRequest orderRequest = new com.paypal.orders.OrderRequest();
            orderRequest.checkoutPaymentIntent("CAPTURE");

            PurchaseUnitRequest purchaseUnit = new PurchaseUnitRequest()
                    .amountWithBreakdown(new AmountWithBreakdown()
                            .currencyCode("GBP")
                            .value(total.toString())
                            .amountBreakdown(new AmountBreakdown()
                                    .itemTotal(new Money().currencyCode("GBP").value(total.toString()))))
                    .items(paypalItems)
                    .description("LICHT LUXE NATURALS Order â€” Made in LOVEWORLD");

            orderRequest.purchaseUnits(Collections.singletonList(purchaseUnit));
            orderRequest.applicationContext(new ApplicationContext()
                    .brandName("LICHT LUXE NATURALS")
                    .landingPage("LOGIN")
                    .userAction("PAY_NOW")
                    .shippingPreference("NO_SHIPPING"));

            OrdersCreateRequest request = new OrdersCreateRequest();
            request.prefer("return=representation");
            request.requestBody(orderRequest);

            HttpResponse<com.paypal.orders.Order> response = payPalHttpClient.execute(request);

            Map<String, String> result = new HashMap<>();
            result.put("id", response.result().id());
            result.put("status", response.result().status());

            return ResponseEntity.ok(result);

        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new MessageResponse("PayPal order creation failed: " + e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new MessageResponse("Error: " + e.getMessage()));
        }
    }

    @PostMapping("/capture-order/{paypalOrderId}")
    public ResponseEntity<?> captureOrder(
            @PathVariable String paypalOrderId,
            @RequestBody(required = false) Map<String, Object> payload) {
        try {
            OrdersCaptureRequest captureRequest = new OrdersCaptureRequest(paypalOrderId);
            captureRequest.prefer("return=representation");

            HttpResponse<com.paypal.orders.Order> response = payPalHttpClient.execute(captureRequest);
            com.paypal.orders.Order paypalOrder = response.result();

            if (!"COMPLETED".equals(paypalOrder.status())) {
                return ResponseEntity.badRequest()
                        .body(new MessageResponse("Payment not completed. Status: " + paypalOrder.status()));
            }

            // Prevent duplicate order creation if already captured
            Optional<Order> existingOrder = orderRepository.findByPaypalOrderId(paypalOrderId);
            if (existingOrder.isPresent()) {
                Order ord = existingOrder.get();
                Map<String, Object> dupResult = new HashMap<>();
                dupResult.put("orderNumber", ord.getOrderNumber());
                dupResult.put("totalAmount", ord.getTotalAmount().toString());
                dupResult.put("status", ord.getStatus().name());
                dupResult.put("paypalOrderId", paypalOrderId);
                dupResult.put("message", "Order already processed.");
                return ResponseEntity.ok(dupResult);
            }

            // Identify or auto-create the Client / Customer
            User user = getOptionalCurrentUser();
            String shippingName = null;
            String shippingEmail = null;
            String shippingPhone = null;
            String shippingAddress = null;
            String shippingCity = null;
            String shippingPostcode = null;

            if (payload != null && payload.containsKey("shippingInfo") && payload.get("shippingInfo") instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, String> sInfo = (Map<String, String>) payload.get("shippingInfo");
                shippingName = sInfo.get("shippingName");
                shippingEmail = sInfo.get("shippingEmail");
                shippingPhone = sInfo.get("shippingPhone");
                shippingAddress = sInfo.get("shippingAddress");
                shippingCity = sInfo.get("shippingCity");
                shippingPostcode = sInfo.get("shippingPostcode");
            }

            // Fallback to PayPal payer info if guest didn't supply email/name
            if (paypalOrder.payer() != null) {
                if ((shippingEmail == null || shippingEmail.isBlank()) && paypalOrder.payer().email() != null) {
                    shippingEmail = paypalOrder.payer().email();
                }
                if ((shippingName == null || shippingName.isBlank()) && paypalOrder.payer().name() != null) {
                    String gName = paypalOrder.payer().name().givenName() != null ? paypalOrder.payer().name().givenName() : "";
                    String sName = paypalOrder.payer().name().surname() != null ? paypalOrder.payer().name().surname() : "";
                    shippingName = (gName + " " + sName).trim();
                }
            }

            if (user == null) {
                String clientEmail = (shippingEmail != null && !shippingEmail.isBlank())
                        ? shippingEmail.trim().toLowerCase()
                        : "guest." + paypalOrderId.toLowerCase() + "@loveworldluxe.com";

                Optional<User> clientOpt = userRepository.findByEmail(clientEmail);
                if (clientOpt.isPresent()) {
                    user = clientOpt.get();
                } else {
                    String firstName = "Customer";
                    String lastName = "";
                    if (shippingName != null && !shippingName.isBlank()) {
                        String[] parts = shippingName.trim().split("\\s+", 2);
                        firstName = parts[0];
                        if (parts.length > 1) lastName = parts[1];
                    }

                    String clientPhone = shippingPhone != null ? shippingPhone.trim() : "";
                    if (!clientPhone.isBlank() && userRepository.existsByPhone(clientPhone)) {
                        clientPhone = ""; // leave blank if collision
                    }

                    User newClient = User.builder()
                            .firstName(firstName)
                            .lastName(lastName)
                            .email(clientEmail)
                            .phone(clientPhone)
                            .password(passwordEncoder.encode(UUID.randomUUID().toString()))
                            .role(User.Role.CUSTOMER)
                            .isActive(true)
                            .build();
                    user = userRepository.save(newClient);
                }
            }

            BigDecimal total = BigDecimal.ZERO;

            Order order = Order.builder()
                    .orderNumber("LLN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                    .user(user)
                    .paypalOrderId(paypalOrderId)
                    .paymentMethod("PAYPAL")
                    .status(Order.OrderStatus.CONFIRMED)
                    .lastNotifiedStatus(Order.OrderStatus.CONFIRMED)
                    .shippingName(shippingName != null && !shippingName.isBlank() ? shippingName : user.getFirstName() + " " + user.getLastName())
                    .shippingPhone(shippingPhone != null && !shippingPhone.isBlank() ? shippingPhone : user.getPhone())
                    .shippingAddress(shippingAddress)
                    .shippingCity(shippingCity)
                    .shippingPostcode(shippingPostcode)
                    .build();

            if (payload != null && payload.containsKey("items") && payload.get("items") instanceof List) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> itemsList = (List<Map<String, Object>>) payload.get("items");
                for (Map<String, Object> itemData : itemsList) {
                    Long prodId = null;
                    if (itemData.containsKey("id") && itemData.get("id") != null) {
                        Object idObj = itemData.get("id");
                        if (idObj instanceof Number) {
                            prodId = ((Number) idObj).longValue();
                        } else {
                            try {
                                prodId = Long.parseLong(String.valueOf(idObj));
                            } catch (Exception ignored) {
                                prodId = null;
                            }
                        }
                    }

                    String prodName = String.valueOf(itemData.getOrDefault("name", "Product"));
                    String slug = String.valueOf(itemData.getOrDefault("id", ""));
                    BigDecimal price = resolveProductPrice(slug, prodName, itemData.get("price"));

                    int qty = 1;
                    if (itemData.containsKey("quantity") && itemData.get("quantity") != null) {
                        Object qtyObj = itemData.get("quantity");
                        if (qtyObj instanceof Number) {
                            qty = ((Number) qtyObj).intValue();
                        } else {
                            try {
                                qty = Integer.parseInt(String.valueOf(qtyObj));
                            } catch (Exception ignored) {
                                qty = 1;
                            }
                        }
                    }

                    Product product = null;
                    if (prodId != null) {
                        product = productRepository.findById(prodId).orElse(null);
                    }
                    if (product == null) {
                        if (!slug.isBlank()) {
                            product = productRepository.findBySlug(slug).orElse(null);
                        }
                    }
                    if (product == null) {
                        List<Product> allProds = productRepository.findAll();
                        for (Product p : allProds) {
                            if (p.getName().equalsIgnoreCase(prodName)) {
                                product = p;
                                break;
                            }
                        }
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
                    if (product != null) {
                        product.setStockQuantity(Math.max(0, product.getStockQuantity() - qty));
                        productRepository.save(product);
                    }
                }
            }

            if (order.getItems().isEmpty() && user != null) {
                Optional<Cart> cartOpt = cartRepository.findByUser(user);
                if (cartOpt.isPresent()) {
                    Cart cart = cartOpt.get();
                    for (CartItem cartItem : cart.getItems()) {
                        BigDecimal itemPrice = cartItem.getUnitPrice().setScale(2, RoundingMode.HALF_UP);
                        total = total.add(itemPrice.multiply(BigDecimal.valueOf(cartItem.getQuantity())));
                        OrderItem orderItem = OrderItem.builder()
                                .product(cartItem.getProduct())
                                .productName(cartItem.getProduct().getName())
                                .quantity(cartItem.getQuantity())
                                .unitPrice(cartItem.getUnitPrice())
                                .lineTotal(cartItem.getUnitPrice().multiply(BigDecimal.valueOf(cartItem.getQuantity())))
                                .build();
                        order.addItem(orderItem);
                    }
                    cart.getItems().clear();
                    cartRepository.save(cart);
                }
            }

            if (total.compareTo(BigDecimal.ZERO) == 0 && payload != null && payload.containsKey("amount")) {
                Object amt = payload.get("amount");
                total = amt instanceof Number
                        ? BigDecimal.valueOf(((Number) amt).doubleValue())
                        : new BigDecimal(String.valueOf(amt));
            }

            order.setCarrier("Royal Mail Tracked 24");
            order.setTrackingNumber("RM" + (System.currentTimeMillis() % 1000000000) + "GB");
            order.setTotalAmount(total.setScale(2, RoundingMode.HALF_UP));
            orderRepository.save(order);

            // Automated Order Confirmation Email triggered from confirmed DB state
            emailService.sendOrderConfirmationEmail(order);

            Map<String, Object> result = new HashMap<>();
            result.put("orderNumber", order.getOrderNumber());
            result.put("totalAmount", order.getTotalAmount().toString());
            result.put("status", order.getStatus().name());
            result.put("paypalOrderId", paypalOrderId);
            result.put("message", "Payment successful! Your order has been placed.");

            return ResponseEntity.ok(result);

        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new MessageResponse("PayPal capture failed: " + e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new MessageResponse("Error: " + e.getMessage()));
        }
    }
}