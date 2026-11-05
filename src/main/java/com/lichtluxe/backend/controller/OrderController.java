package com.lichtluxe.backend.controller;

import com.lichtluxe.backend.dto.MessageResponse;
import com.lichtluxe.backend.dto.OrderRequest;
import com.lichtluxe.backend.dto.OrderStatusUpdateRequest;
import com.lichtluxe.backend.entity.*;
import com.lichtluxe.backend.repository.CartRepository;
import com.lichtluxe.backend.repository.OrderRepository;
import com.lichtluxe.backend.repository.ProductRepository;
import com.lichtluxe.backend.repository.UserRepository;
import com.lichtluxe.backend.service.EmailService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Controller for handling storefront orders, checkout, and status tracking.
 * Every purchase (Card, PayPal, Espees) is permanently stored in the database.
 */
@CrossOrigin(origins = "*", maxAge = 3600)
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private EmailService emailService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private User getCurrentUser() {
        org.springframework.security.core.Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            throw new com.lichtluxe.backend.exception.UnauthorizedException("You must be signed in to perform this action.");
        }
        return userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new com.lichtluxe.backend.exception.ResourceNotFoundException("User account not found: " + auth.getName()));
    }

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
                : "guest." + orderRef.toLowerCase() + "@loveworldluxe.com";

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

    @GetMapping
    public List<Order> getUserOrders() {
        return orderRepository.findByUserOrderByCreatedAtDesc(getCurrentUser());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getOrderById(@PathVariable Long id) {
        User user = getCurrentUser();
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new com.lichtluxe.backend.exception.ResourceNotFoundException("Order not found with id: " + id));

        // IDOR Protection: verify order belongs to current user or user is ADMIN
        if (!order.getUser().getId().equals(user.getId()) && user.getRole() != User.Role.ADMIN) {
            throw new org.springframework.security.access.AccessDeniedException("You do not have permission to view this order.");
        }
        return ResponseEntity.ok(order);
    }

    /**
     * Unified checkout endpoint for any completed storefront purchase (Card, PayPal, Espees).
     * Saves order, items, customer, decrements inventory, and dispatches confirmation email.
     */
    @PostMapping("/checkout")
    public ResponseEntity<?> checkoutStorefrontOrder(@RequestBody Map<String, Object> payload) {
        try {
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
            } else if (payload.containsKey("customer") && payload.get("customer") instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, String> cInfo = (Map<String, String>) payload.get("customer");
                shippingName = cInfo.get("name");
                shippingEmail = cInfo.get("email");
                shippingPhone = cInfo.get("phone");
                shippingAddress = cInfo.get("address");
            }

            // Generate or adopt order number
            String orderNumber = null;
            if (payload.containsKey("orderNumber") && payload.get("orderNumber") != null) {
                orderNumber = String.valueOf(payload.get("orderNumber")).trim();
            } else if (payload.containsKey("id") && payload.get("id") != null) {
                orderNumber = String.valueOf(payload.get("id")).trim();
            }
            if (orderNumber == null || orderNumber.isBlank() || orderRepository.findByOrderNumber(orderNumber).isPresent()) {
                orderNumber = "LLN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            }

            User customer = getOrCreateCustomer(shippingName, shippingEmail, shippingPhone, orderNumber);

            String paymentMethod = payload.containsKey("paymentMethod") && payload.get("paymentMethod") != null
                    ? String.valueOf(payload.get("paymentMethod"))
                    : "Credit / Debit Card";

            String courier = payload.containsKey("courier") && payload.get("courier") != null
                    ? String.valueOf(payload.get("courier"))
                    : "Royal Mail Tracked 48";

            String trackingNumber = payload.containsKey("trackingNumber") && payload.get("trackingNumber") != null
                    ? String.valueOf(payload.get("trackingNumber"))
                    : "RM" + (100000000L + (long)(Math.random() * 899999999L)) + "GB";

            String paypalOrderId = payload.containsKey("paypalOrderId") && payload.get("paypalOrderId") != null
                    ? String.valueOf(payload.get("paypalOrderId"))
                    : null;

            Order order = Order.builder()
                    .orderNumber(orderNumber)
                    .user(customer)
                    .paypalOrderId(paypalOrderId)
                    .paymentMethod(paymentMethod)
                    .status(Order.OrderStatus.CONFIRMED)
                    .lastNotifiedStatus(Order.OrderStatus.CONFIRMED)
                    .shippingName(shippingName != null && !shippingName.isBlank() ? shippingName : customer.getFirstName() + " " + customer.getLastName())
                    .shippingPhone(shippingPhone != null && !shippingPhone.isBlank() ? shippingPhone : customer.getPhone())
                    .shippingAddress(shippingAddress)
                    .shippingCity(shippingCity)
                    .shippingPostcode(shippingPostcode)
                    .carrier(courier)
                    .trackingNumber(trackingNumber)
                    .orderNotes(orderNotes)
                    .build();

            BigDecimal total = BigDecimal.ZERO;

            if (payload.containsKey("items") && payload.get("items") instanceof List) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> itemsList = (List<Map<String, Object>>) payload.get("items");
                for (Map<String, Object> itemData : itemsList) {
                    String prodName = String.valueOf(itemData.getOrDefault("name", "Pure Botanical Product"));
                    String slug = String.valueOf(itemData.getOrDefault("id", ""));

                    BigDecimal price = BigDecimal.ZERO;
                    if (itemData.containsKey("price") && itemData.get("price") != null) {
                        try {
                            price = new BigDecimal(String.valueOf(itemData.get("price"))).setScale(2, RoundingMode.HALF_UP);
                        } catch (Exception ignored) {}
                    }

                    int qty = 1;
                    if (itemData.containsKey("quantity") && itemData.get("quantity") != null) {
                        try {
                            qty = Integer.parseInt(String.valueOf(itemData.get("quantity")));
                        } catch (Exception ignored) {}
                    } else if (itemData.containsKey("qty") && itemData.get("qty") != null) {
                        try {
                            qty = Integer.parseInt(String.valueOf(itemData.get("qty")));
                        } catch (Exception ignored) {}
                    }

                    Product product = null;
                    if (!slug.isBlank()) {
                        product = productRepository.findBySlug(slug).orElse(null);
                    }
                    if (product == null && !prodName.isBlank()) {
                        product = productRepository.findByName(prodName).orElse(null);
                    }

                    if (price.compareTo(BigDecimal.ZERO) <= 0 && product != null) {
                        price = product.getPrice();
                    }

                    // Decrement inventory stock in DB
                    if (product != null) {
                        int currentStock = product.getStockQuantity();
                        product.setStockQuantity(Math.max(0, currentStock - qty));
                        productRepository.save(product);
                    }

                    BigDecimal lineTotal = price.multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP);
                    total = total.add(lineTotal);

                    OrderItem orderItem = OrderItem.builder()
                            .product(product)
                            .productName(prodName)
                            .quantity(qty)
                            .unitPrice(price)
                            .lineTotal(lineTotal)
                            .build();
                    order.addItem(orderItem);
                }
            }

            // Total override or delivery fee addition
            if (payload.containsKey("deliveryFee") && payload.get("deliveryFee") != null) {
                try {
                    BigDecimal dFee = new BigDecimal(String.valueOf(payload.get("deliveryFee")));
                    total = total.add(dFee);
                } catch (Exception ignored) {}
            }

            if (payload.containsKey("total") && payload.get("total") != null) {
                try {
                    BigDecimal t = new BigDecimal(String.valueOf(payload.get("total")));
                    if (t.compareTo(BigDecimal.ZERO) > 0) {
                        total = t;
                    }
                } catch (Exception ignored) {}
            } else if (payload.containsKey("totalAmount") && payload.get("totalAmount") != null) {
                try {
                    BigDecimal t = new BigDecimal(String.valueOf(payload.get("totalAmount")));
                    if (t.compareTo(BigDecimal.ZERO) > 0) {
                        total = t;
                    }
                } catch (Exception ignored) {}
            }

            order.setTotalAmount(total.setScale(2, RoundingMode.HALF_UP));
            Order savedOrder = orderRepository.save(order);

            // Send confirmation email asynchronously
            try {
                emailService.sendOrderConfirmationEmail(savedOrder);
            } catch (Exception ignored) {}

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("id", savedOrder.getId());
            resp.put("orderNumber", savedOrder.getOrderNumber());
            resp.put("totalAmount", savedOrder.getTotalAmount().toString());
            resp.put("status", savedOrder.getStatus().name());
            resp.put("paymentMethod", savedOrder.getPaymentMethod());
            resp.put("carrier", savedOrder.getCarrier());
            resp.put("trackingNumber", savedOrder.getTrackingNumber());
            resp.put("customerEmail", customer.getEmail());

            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new MessageResponse("Failed to record order: " + e.getMessage()));
        }
    }

    @PostMapping("/place")
    public ResponseEntity<?> placeOrder(@RequestBody OrderRequest request) {
        User user = getCurrentUser();
        Cart cart = cartRepository.findByUser(user)
                .orElseThrow(() -> new RuntimeException("Cart not found"));

        if (cart.getItems().isEmpty()) {
            return ResponseEntity.badRequest().body(new MessageResponse("Cart is empty"));
        }

        BigDecimal total = cart.getItems().stream()
                .map(item -> item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Order order = Order.builder()
                .orderNumber("LLN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .user(user)
                .totalAmount(total)
                .status(Order.OrderStatus.CONFIRMED)
                .lastNotifiedStatus(Order.OrderStatus.CONFIRMED)
                .paymentMethod("ONLINE")
                .shippingName(request.getShippingName() != null ? request.getShippingName() : user.getFirstName() + " " + user.getLastName())
                .shippingAddress(request.getShippingAddress())
                .shippingCity(request.getShippingCity())
                .shippingPostcode(request.getShippingPostcode())
                .shippingPhone(request.getShippingPhone())
                .orderNotes(request.getOrderNotes())
                .build();

        for (CartItem cartItem : cart.getItems()) {
            OrderItem orderItem = OrderItem.builder()
                    .product(cartItem.getProduct())
                    .productName(cartItem.getProduct().getName())
                    .quantity(cartItem.getQuantity())
                    .unitPrice(cartItem.getUnitPrice())
                    .lineTotal(cartItem.getUnitPrice().multiply(BigDecimal.valueOf(cartItem.getQuantity())))
                    .build();
            order.addItem(orderItem);
        }

        orderRepository.save(order);

        // Automated Order Confirmation Email
        emailService.sendOrderConfirmationEmail(order);

        // Clear cart
        cart.getItems().clear();
        cartRepository.save(cart);

        return ResponseEntity.ok(order);
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<?> updateOrderStatus(
            @PathVariable Long id,
            @Valid @RequestBody OrderStatusUpdateRequest request) {

        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Order not found with id: " + id));

        Order.OrderStatus previousStatus = order.getStatus();
        Order.OrderStatus newStatus = request.getStatus();

        order.setStatus(newStatus);
        if (request.getCarrier() != null && !request.getCarrier().isBlank()) {
            order.setCarrier(request.getCarrier());
        }
        if (request.getTrackingNumber() != null && !request.getTrackingNumber().isBlank()) {
            order.setTrackingNumber(request.getTrackingNumber());
        }
        if (request.getTrackingUrl() != null && !request.getTrackingUrl().isBlank()) {
            order.setTrackingUrl(request.getTrackingUrl());
        }
        if (request.getNotes() != null && !request.getNotes().isBlank()) {
            order.setOrderNotes(request.getNotes());
        }

        if (order.getLastNotifiedStatus() == null || !order.getLastNotifiedStatus().equals(newStatus)) {
            emailService.sendOrderStatusEmail(order, newStatus);
            order.setLastNotifiedStatus(newStatus);
        }

        orderRepository.save(order);

        return ResponseEntity.ok(order);
    }
}
