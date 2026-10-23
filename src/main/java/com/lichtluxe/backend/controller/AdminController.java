package com.lichtluxe.backend.controller;

import com.lichtluxe.backend.dto.MessageResponse;
import com.lichtluxe.backend.entity.Order;
import com.lichtluxe.backend.entity.OrderItem;
import com.lichtluxe.backend.entity.Product;
import com.lichtluxe.backend.entity.User;
import com.lichtluxe.backend.repository.OrderRepository;
import com.lichtluxe.backend.repository.ProductRepository;
import com.lichtluxe.backend.repository.UserRepository;
import com.lichtluxe.backend.security.JwtUtils;
import com.lichtluxe.backend.service.EmailService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@CrossOrigin(origins = "*", maxAge = 3600)
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmailService emailService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtils jwtUtils;

    private boolean verifyAdminAccess(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return false;
        }
        String jwt = authHeader.substring(7);
        if (!jwtUtils.validateJwtToken(jwt)) {
            return false;
        }
        String email = jwtUtils.getUserNameFromJwtToken(jwt);
        Optional<User> userOpt = userRepository.findByEmail(email);
        return userOpt.isPresent() && userOpt.get().getRole() == User.Role.ADMIN;
    }

    // --- 1. REAL ORDERS MANAGEMENT -------------------------------------
    @GetMapping("/orders")
    public ResponseEntity<?> getAllOrders(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        List<Order> orders = orderRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(orders);
    }

    @PutMapping("/orders/{id}/status")
    public ResponseEntity<?> updateOrderStatus(
            @PathVariable Long id,
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        Optional<Order> orderOpt = orderRepository.findById(id);
        if (orderOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new MessageResponse("Order not found with id: " + id));
        }

        Order order = orderOpt.get();
        if (payload.containsKey("status")) {
            String statusStr = String.valueOf(payload.get("status")).toUpperCase();
            try {
                Order.OrderStatus newStatus = Order.OrderStatus.valueOf(statusStr);
                order.setStatus(newStatus);

                if (payload.containsKey("carrier") && payload.get("carrier") != null) {
                    order.setCarrier(String.valueOf(payload.get("carrier")));
                }
                if (payload.containsKey("trackingNumber") && payload.get("trackingNumber") != null) {
                    order.setTrackingNumber(String.valueOf(payload.get("trackingNumber")));
                }
                if (payload.containsKey("trackingUrl") && payload.get("trackingUrl") != null) {
                    order.setTrackingUrl(String.valueOf(payload.get("trackingUrl")));
                }

                // Dispatch automated email notification
                emailService.sendOrderStatusEmail(order, newStatus);
                order.setLastNotifiedStatus(newStatus);

            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(new MessageResponse("Invalid order status: " + statusStr));
            }
        }

        orderRepository.save(order);
        return ResponseEntity.ok(order);
    }

    @PostMapping("/orders/create")
    public ResponseEntity<?> createManualOrder(
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        User orderUser = null;
        if (payload.containsKey("customerEmail") && payload.get("customerEmail") != null) {
            orderUser = userRepository.findByEmailIgnoreCase(String.valueOf(payload.get("customerEmail")).trim()).orElse(null);
        }
        if (orderUser == null) {
            orderUser = userRepository.findByEmailIgnoreCase("sharonshelke1@gmail.com").orElse(null);
        }
        if (orderUser == null) {
            orderUser = userRepository.findAll().stream().findFirst().orElse(null);
        }

        // Generate guaranteed unique order number without duplicates
        String orderNumber;
        long seq = orderRepository.count() + 1;
        do {
            orderNumber = "LN-" + (1080 + seq);
            seq++;
        } while (orderRepository.findByOrderNumber(orderNumber).isPresent());

        BigDecimal total = BigDecimal.valueOf(payload.containsKey("total") ? ((Number) payload.get("total")).doubleValue() : 24.00);

        Order order = Order.builder()
                .orderNumber(orderNumber)
                .user(orderUser)
                .totalAmount(total)
                .status(Order.OrderStatus.CONFIRMED)
                .lastNotifiedStatus(Order.OrderStatus.CONFIRMED)
                .paymentMethod(payload.containsKey("paymentMethod") ? String.valueOf(payload.get("paymentMethod")) : "Manual Admin Order")
                .shippingName(payload.containsKey("customerName") ? String.valueOf(payload.get("customerName")) : "Client Order")
                .shippingAddress(payload.containsKey("address") ? String.valueOf(payload.get("address")) : "London, UK")
                .shippingCity("London")
                .shippingPostcode("W8 4PT")
                .carrier(payload.containsKey("courier") ? String.valueOf(payload.get("courier")) : "Royal Mail Tracked 24")
                .trackingNumber(payload.containsKey("trackingNumber") ? String.valueOf(payload.get("trackingNumber")) : "RM" + System.currentTimeMillis() % 1000000000 + "GB")
                .build();

        orderRepository.save(order);
        return ResponseEntity.ok(order);
    }

    // --- 2. REAL INVENTORY MANAGEMENT ----------------------------------
    @GetMapping("/inventory")
    public ResponseEntity<?> getAllInventory(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        List<Product> products = productRepository.findAll();
        return ResponseEntity.ok(products);
    }

    @PutMapping("/inventory/{id}/restock")
    public ResponseEntity<?> restockProduct(
            @PathVariable Long id,
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        Optional<Product> prodOpt = productRepository.findById(id);
        if (prodOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new MessageResponse("Product not found"));
        }

        Product product = prodOpt.get();
        int amount = payload.containsKey("amount") ? ((Number) payload.get("amount")).intValue() : 25;
        product.setStockQuantity(product.getStockQuantity() + amount);
        productRepository.save(product);

        return ResponseEntity.ok(Map.of(
                "message", "Restocked successfully",
                "productId", product.getId(),
                "newStock", product.getStockQuantity()
        ));
    }

    // --- 3. USER & TEAM ACCESS CONTROL (PROMOTE ADMIN) -----------------
    @GetMapping("/users")
    public ResponseEntity<?> getAllUsers(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        List<User> users = userRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
        List<Map<String, Object>> response = new ArrayList<>();
        for (User u : users) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", u.getId());
            map.put("firstName", u.getFirstName());
            map.put("lastName", u.getLastName());
            map.put("name", u.getFirstName() + " " + u.getLastName());
            map.put("email", u.getEmail());
            map.put("phone", u.getPhone() != null ? u.getPhone() : "");
            map.put("role", u.getRole() != null ? u.getRole().name() : "CUSTOMER");
            map.put("isActive", u.isActive());
            map.put("createdAt", u.getCreatedAt());
            response.add(map);
        }
        return ResponseEntity.ok(response);
    }

    @PostMapping("/users/promote")
    public ResponseEntity<?> promoteUserToAdmin(
            @RequestBody Map<String, String> payload,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        String email = payload.get("email");
        if (email == null || email.isBlank()) {
            return ResponseEntity.badRequest().body(new MessageResponse("Email is required"));
        }

        Optional<User> userOpt = userRepository.findByEmail(email.trim().toLowerCase());
        if (userOpt.isEmpty()) {
            // Also check case-insensitive match
            List<User> all = userRepository.findAll();
            for (User u : all) {
                if (u.getEmail().equalsIgnoreCase(email.trim())) {
                    userOpt = Optional.of(u);
                    break;
                }
            }
        }

        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new MessageResponse("No user found with email: " + email));
        }

        User user = userOpt.get();
        user.setRole(User.Role.ADMIN);
        userRepository.save(user);

        return ResponseEntity.ok(new MessageResponse("User " + user.getEmail() + " is now an Administrator!"));
    }

    @PostMapping("/users/create")
    public ResponseEntity<?> createAdminUser(
            @RequestBody Map<String, String> payload,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        String email = payload.get("email");
        if (email == null || email.isBlank()) {
            return ResponseEntity.badRequest().body(new MessageResponse("Email is required"));
        }

        String cleanEmail = email.trim().toLowerCase();
        if (userRepository.existsByEmailIgnoreCase(cleanEmail)) {
            return ResponseEntity.badRequest().body(new MessageResponse("An account with this email address already exists!"));
        }

        String phone = payload.get("phone");
        if (phone != null && !phone.trim().isBlank() && userRepository.existsByPhone(phone.trim())) {
            return ResponseEntity.badRequest().body(new MessageResponse("An account with this phone number already exists!"));
        }

        String rawPassword = payload.getOrDefault("password", "Luxe@123&");
        String roleStr = payload.getOrDefault("role", "ADMIN").toUpperCase();
        User.Role role = roleStr.contains("ADMIN") ? User.Role.ADMIN : User.Role.CUSTOMER;

        User user = User.builder()
                .firstName(payload.getOrDefault("firstName", "Admin"))
                .lastName(payload.getOrDefault("lastName", "User"))
                .email(cleanEmail)
                .phone(phone != null && !phone.trim().isBlank() ? phone.trim() : null)
                .password(passwordEncoder.encode(rawPassword))
                .role(role)
                .isActive(true)
                .build();

        userRepository.save(user);

        return ResponseEntity.ok(new MessageResponse("Successfully created user " + user.getEmail() + " with role " + role.name()));
    }

    @Autowired
    private com.lichtluxe.backend.repository.EmailLogRepository emailLogRepository;

    @GetMapping("/email-logs")
    public ResponseEntity<?> getEmailLogs(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (!verifyAdminAccess(authHeader)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new MessageResponse("Unauthorized admin access."));
        }
        return ResponseEntity.ok(emailLogRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt")));
    }
}
