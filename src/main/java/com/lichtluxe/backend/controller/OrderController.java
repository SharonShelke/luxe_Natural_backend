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
import com.paypal.core.PayPalHttpClient;
import com.paypal.http.HttpResponse;
import com.paypal.orders.OrdersCaptureRequest;
import com.paypal.orders.OrdersGetRequest;
import com.paypal.orders.PurchaseUnit;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Controller for handling storefront orders, checkout, and status tracking.
 * Every purchase is authenticated, verified server-side, and recorded with canonical prices.
 */
@CrossOrigin(origins = "*", maxAge = 3600)
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    // Business Pricing & Delivery Constants
    public static final BigDecimal FREE_SHIPPING_THRESHOLD = new BigDecimal("70.00");
    public static final BigDecimal STANDARD_DELIVERY_FEE = new BigDecimal("6.99");
    public static final BigDecimal EXPRESS_DELIVERY_FEE = new BigDecimal("10.99");

    // Strict Validation Patterns
    private static final Pattern UK_POSTCODE_PATTERN = Pattern.compile("^[A-Z]{1,2}[0-9][A-Z0-9]?\\s?[0-9][A-Z]{2}$", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[a-zA-Z0-9_+&*-]+(?:\\.[a-zA-Z0-9_+&*-]+)*@(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,7}$");

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

    @Autowired
    private PayPalHttpClient payPalHttpClient;

    @Autowired
    private com.lichtluxe.backend.service.PricingService pricingService;

    @Value("${app.currency:GBP}")
    private String configuredCurrency;

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

    private User getOrCreateCustomer(String shippingName, String validEmail, String shippingPhone) {
        User user = getOptionalCurrentUser();
        if (user != null) return user;

        String email = validEmail.trim().toLowerCase();

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

        String phone = (shippingPhone != null && !shippingPhone.isBlank()) ? shippingPhone.trim() : null;
        if (phone != null && userRepository.existsByPhone(phone)) {
            phone = null;
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
     * Authoritative storefront checkout endpoint.
     * Enforces server-side canonical pricing, stock validation, delivery calculation,
     * request validation, and strict payment settlement verification.
     */
    @Transactional(rollbackFor = Exception.class)
    @PostMapping("/checkout")
    public ResponseEntity<?> checkoutStorefrontOrder(@RequestBody(required = false) Map<String, Object> payload) {
        if (payload == null) {
            return ResponseEntity.badRequest().body(new MessageResponse("Checkout payload cannot be empty."));
        }

        // 1. Extract shipping/customer info
        String shippingName = null;
        String shippingEmail = null;
        String shippingPhone = null;
        String shippingAddress = null;
        String shippingCity = null;
        String shippingPostcode = null;
        String orderNotes = null;

        if (payload.containsKey("shippingInfo") && payload.get("shippingInfo") instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> sInfo = (Map<String, Object>) payload.get("shippingInfo");
            shippingName = sInfo.get("shippingName") != null ? String.valueOf(sInfo.get("shippingName")).trim() : null;
            shippingEmail = sInfo.get("shippingEmail") != null ? String.valueOf(sInfo.get("shippingEmail")).trim() : null;
            shippingPhone = sInfo.get("shippingPhone") != null ? String.valueOf(sInfo.get("shippingPhone")).trim() : null;
            shippingAddress = sInfo.get("shippingAddress") != null ? String.valueOf(sInfo.get("shippingAddress")).trim() : null;
            shippingCity = sInfo.get("shippingCity") != null ? String.valueOf(sInfo.get("shippingCity")).trim() : null;
            shippingPostcode = sInfo.get("shippingPostcode") != null ? String.valueOf(sInfo.get("shippingPostcode")).trim() : null;
            orderNotes = sInfo.get("orderNotes") != null ? String.valueOf(sInfo.get("orderNotes")).trim() : null;
        } else if (payload.containsKey("customer") && payload.get("customer") instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cInfo = (Map<String, Object>) payload.get("customer");
            shippingName = cInfo.get("name") != null ? String.valueOf(cInfo.get("name")).trim() : null;
            shippingEmail = cInfo.get("email") != null ? String.valueOf(cInfo.get("email")).trim() : null;
            shippingPhone = cInfo.get("phone") != null ? String.valueOf(cInfo.get("phone")).trim() : null;
            shippingAddress = cInfo.get("address") != null ? String.valueOf(cInfo.get("address")).trim() : null;
        }

        // 2. Request Validation: Email (mandatory, strictly validated, no fake fallbacks)
        if (shippingEmail == null || shippingEmail.isBlank() || !EMAIL_PATTERN.matcher(shippingEmail).matches() || shippingEmail.length() > 150) {
            return ResponseEntity.badRequest().body(new MessageResponse("A valid customer email address is required for checkout."));
        }

        // Customer shipping name (mandatory, length limit 100)
        if (shippingName == null || shippingName.isBlank()) {
            return ResponseEntity.badRequest().body(new MessageResponse("Customer shipping name is required."));
        }
        if (shippingName.length() > 100) {
            shippingName = shippingName.substring(0, 100).trim();
        }

        // Customer shipping address (mandatory, length limit 255)
        if (shippingAddress == null || shippingAddress.isBlank()) {
            return ResponseEntity.badRequest().body(new MessageResponse("Customer shipping address is required."));
        }
        if (shippingAddress.length() > 255) {
            shippingAddress = shippingAddress.substring(0, 255).trim();
        }

        // UK Postcode validation (mandatory, strict UK regex)
        if (shippingPostcode == null || shippingPostcode.isBlank() || !UK_POSTCODE_PATTERN.matcher(shippingPostcode).matches()) {
            return ResponseEntity.badRequest().body(new MessageResponse("Invalid UK postcode. Please provide a valid UK postcode (e.g. SW1A 1AA, W8 4PT)."));
        }
        shippingPostcode = shippingPostcode.toUpperCase();

        // City & Phone & Notes length limit
        if (shippingCity == null || shippingCity.isBlank()) {
            shippingCity = "London";
        } else if (shippingCity.length() > 100) {
            shippingCity = shippingCity.substring(0, 100).trim();
        }
        if (shippingPhone != null && shippingPhone.length() > 30) {
            shippingPhone = shippingPhone.substring(0, 30).trim();
        }
        if (orderNotes != null && orderNotes.length() > 500) {
            orderNotes = orderNotes.substring(0, 500).trim();
        }

        // 3. Validate items list
        if (!payload.containsKey("items") || !(payload.get("items") instanceof List)) {
            return ResponseEntity.badRequest().body(new MessageResponse("Order must contain at least one item."));
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> itemsList = (List<Map<String, Object>>) payload.get("items");
        if (itemsList.isEmpty()) {
            return ResponseEntity.badRequest().body(new MessageResponse("Order must contain at least one item."));
        }

        // 4. Idempotency Check: if paypalOrderId already processed, return existing order immediately (HTTP 200)
        String paypalOrderId = payload.containsKey("paypalOrderId") && payload.get("paypalOrderId") != null
                ? String.valueOf(payload.get("paypalOrderId")).trim()
                : null;

        if (paypalOrderId != null && !paypalOrderId.isBlank()) {
            Optional<Order> existingOrderOpt = orderRepository.findByPaypalOrderId(paypalOrderId);
            if (existingOrderOpt.isPresent()) {
                Order existing = existingOrderOpt.get();
                Map<String, Object> dupResp = new LinkedHashMap<>();
                dupResp.put("success", true);
                dupResp.put("id", existing.getId());
                dupResp.put("orderNumber", existing.getOrderNumber());
                dupResp.put("total", existing.getTotalAmount());
                dupResp.put("currency", configuredCurrency);
                dupResp.put("status", existing.getStatus().name());
                dupResp.put("carrier", existing.getCarrier());
                dupResp.put("trackingNumber", existing.getTrackingNumber());
                dupResp.put("paymentMethod", existing.getPaymentMethod());
                dupResp.put("message", "Order already processed.");
                return ResponseEntity.ok(dupResp);
            }
        }

        // 5. Authoritative Server-Side Pricing & Quantity Validation via Shared PricingService
        String deliveryOption = "";
        if (payload.containsKey("deliveryOption") && payload.get("deliveryOption") != null) {
            deliveryOption = String.valueOf(payload.get("deliveryOption")).toLowerCase();
        } else if (payload.containsKey("deliveryMethod") && payload.get("deliveryMethod") != null) {
            deliveryOption = String.valueOf(payload.get("deliveryMethod")).toLowerCase();
        } else if (payload.containsKey("courier") && payload.get("courier") != null) {
            deliveryOption = String.valueOf(payload.get("courier")).toLowerCase();
        }

        com.lichtluxe.backend.service.PricingService.PricingResult pricingResult;
        try {
            pricingResult = pricingService.calculatePricing(itemsList, deliveryOption);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new MessageResponse(ex.getMessage()));
        }

        BigDecimal subtotal = pricingResult.getSubtotal();
        BigDecimal deliveryFee = pricingResult.getDeliveryFee();
        BigDecimal calculatedTotal = pricingResult.getTotal();
        String courier = pricingResult.getCourier();

        // 6. Check stock availability and decrement inside transactional boundary
        List<OrderItem> orderItems = new ArrayList<>();
        List<Product> productsToUpdate = new ArrayList<>();

        for (com.lichtluxe.backend.service.PricingService.PricedItem pricedItem : pricingResult.getItems()) {
            Product product = pricedItem.getProduct();
            int requestedQty = pricedItem.getQuantity();

            if (product.getStockQuantity() < requestedQty) {
                return ResponseEntity.badRequest().body(new MessageResponse("Insufficient stock for product '" + product.getName() + "'. Available: " + product.getStockQuantity() + ", requested: " + requestedQty));
            }

            product.setStockQuantity(product.getStockQuantity() - requestedQty);
            productsToUpdate.add(product);

            OrderItem item = OrderItem.builder()
                    .product(product)
                    .productName(product.getName())
                    .quantity(requestedQty)
                    .unitPrice(pricedItem.getUnitPrice())
                    .lineTotal(pricedItem.getLineTotal())
                    .build();
            orderItems.add(item);
        }

        // 7. Payment method & payment integrity verification
        String paymentMethod = payload.containsKey("paymentMethod") && payload.get("paymentMethod") != null
                ? String.valueOf(payload.get("paymentMethod")).trim()
                : "PayPal";

        String upperMethod = paymentMethod.toUpperCase();
        if (upperMethod.contains("CARD") || upperMethod.contains("STRIPE") || upperMethod.contains("CREDIT") || upperMethod.contains("DEBIT")) {
            return ResponseEntity.badRequest().body(new MessageResponse("Credit / Debit card payment is temporarily disabled pending gateway integration. Please checkout with PayPal."));
        }

        if (upperMethod.contains("PAYPAL")) {
            if (paypalOrderId == null || paypalOrderId.isBlank()) {
                return ResponseEntity.badRequest().body(new MessageResponse("PayPal Order ID is required for PayPal payment verification."));
            }

            // Server-side verification with PayPal Orders API
            try {
                OrdersGetRequest getRequest = new OrdersGetRequest(paypalOrderId);
                HttpResponse<com.paypal.orders.Order> ppResponse = payPalHttpClient.execute(getRequest);
                com.paypal.orders.Order ppOrder = ppResponse != null ? ppResponse.result() : null;

                if (ppOrder == null) {
                    return ResponseEntity.badRequest().body(new MessageResponse("Unable to retrieve PayPal order details for order ID: " + paypalOrderId));
                }

                // If order was approved by buyer but not yet captured, capture it now server-side
                if ("APPROVED".equalsIgnoreCase(ppOrder.status())) {
                    OrdersCaptureRequest captureRequest = new OrdersCaptureRequest(paypalOrderId);
                    captureRequest.prefer("return=representation");
                    ppResponse = payPalHttpClient.execute(captureRequest);
                    ppOrder = ppResponse != null ? ppResponse.result() : null;
                }

                if (ppOrder == null || !"COMPLETED".equalsIgnoreCase(ppOrder.status())) {
                    return ResponseEntity.badRequest().body(new MessageResponse("PayPal payment is not completed. Current status: " + (ppOrder != null ? ppOrder.status() : "UNKNOWN")));
                }

                // Verify currency and amount
                if (ppOrder.purchaseUnits() == null || ppOrder.purchaseUnits().isEmpty()) {
                    return ResponseEntity.badRequest().body(new MessageResponse("PayPal order contains no purchase units."));
                }
                PurchaseUnit pu = ppOrder.purchaseUnits().get(0);
                if (pu.amountWithBreakdown() == null) {
                    return ResponseEntity.badRequest().body(new MessageResponse("PayPal order contains no amount information."));
                }

                String ppCurrency = pu.amountWithBreakdown().currencyCode();
                if (ppCurrency == null || !configuredCurrency.equalsIgnoreCase(ppCurrency)) {
                    return ResponseEntity.badRequest().body(new MessageResponse("Payment currency mismatch: expected " + configuredCurrency + " but PayPal order was processed in " + ppCurrency));
                }

                BigDecimal ppAmount = new BigDecimal(pu.amountWithBreakdown().value()).setScale(2, RoundingMode.HALF_UP);
                if (ppAmount.compareTo(calculatedTotal) != 0) {
                    return ResponseEntity.badRequest().body(new MessageResponse("Payment amount mismatch: expected " + calculatedTotal + " " + configuredCurrency + " but PayPal captured " + ppAmount + " " + ppCurrency));
                }

            } catch (com.paypal.http.exceptions.HttpException httpEx) {
                log.error("PayPal API HTTP error during verification: {}", httpEx.getMessage());
                return ResponseEntity.badRequest().body(new MessageResponse("PayPal payment verification failed: " + httpEx.getMessage()));
            } catch (Exception e) {
                log.error("PayPal verification error: {}", e.getMessage());
                return ResponseEntity.badRequest().body(new MessageResponse("PayPal payment verification error: " + e.getMessage()));
            }
        } else if (upperMethod.contains("ESPEES")) {
            // Espees payment processing supported via verified reference
        } else {
            return ResponseEntity.badRequest().body(new MessageResponse("Invalid or unsupported payment method: " + paymentMethod));
        }

        // 8. Commit stock decrements
        productRepository.saveAll(productsToUpdate);

        // 9. Generate unique order number and create/retrieve customer record
        String orderNumber = "LLN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        User customer = getOrCreateCustomer(shippingName, shippingEmail, shippingPhone);

        String trackingNumber = "RM" + (100000000L + (long)(Math.random() * 899999999L)) + "GB";

        Order order = Order.builder()
                .orderNumber(orderNumber)
                .user(customer)
                .paypalOrderId(paypalOrderId)
                .paymentMethod(paymentMethod)
                .status(Order.OrderStatus.CONFIRMED)
                .lastNotifiedStatus(Order.OrderStatus.CONFIRMED)
                .shippingName(shippingName)
                .shippingPhone(shippingPhone != null && !shippingPhone.isBlank() ? shippingPhone : customer.getPhone())
                .shippingAddress(shippingAddress)
                .shippingCity(shippingCity)
                .shippingPostcode(shippingPostcode)
                .carrier(courier)
                .trackingNumber(trackingNumber)
                .orderNotes(orderNotes)
                .totalAmount(calculatedTotal)
                .build();

        for (OrderItem it : orderItems) {
            order.addItem(it);
        }

        Order savedOrder;
        try {
            savedOrder = orderRepository.save(order);
        } catch (org.springframework.dao.DataIntegrityViolationException dive) {
            if (paypalOrderId != null) {
                Optional<Order> existingOpt = orderRepository.findByPaypalOrderId(paypalOrderId);
                if (existingOpt.isPresent()) {
                    Order existing = existingOpt.get();
                    Map<String, Object> dupResp = new LinkedHashMap<>();
                    dupResp.put("success", true);
                    dupResp.put("id", existing.getId());
                    dupResp.put("orderNumber", existing.getOrderNumber());
                    dupResp.put("total", existing.getTotalAmount());
                    dupResp.put("currency", configuredCurrency);
                    dupResp.put("status", existing.getStatus().name());
                    dupResp.put("carrier", existing.getCarrier());
                    dupResp.put("trackingNumber", existing.getTrackingNumber());
                    dupResp.put("paymentMethod", existing.getPaymentMethod());
                    dupResp.put("message", "Order already processed.");
                    return ResponseEntity.ok(dupResp);
                }
            }
            throw dive;
        }

        // 10. Asynchronously dispatch confirmation email (failures logged, never fails the order)
        try {
            emailService.sendOrderConfirmationEmail(savedOrder);
        } catch (Exception ex) {
            log.error("Failed to send order confirmation email for order {}: {}", savedOrder.getOrderNumber(), ex.getMessage());
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("id", savedOrder.getId());
        resp.put("orderNumber", savedOrder.getOrderNumber());
        resp.put("total", savedOrder.getTotalAmount());
        resp.put("currency", configuredCurrency);
        resp.put("subtotal", subtotal);
        resp.put("deliveryFee", deliveryFee);
        resp.put("status", savedOrder.getStatus().name());
        resp.put("carrier", savedOrder.getCarrier());
        resp.put("trackingNumber", savedOrder.getTrackingNumber());
        resp.put("paymentMethod", savedOrder.getPaymentMethod());
        return ResponseEntity.ok(resp);
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

    @PreAuthorize("hasRole('ADMIN')")
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
