package com.lichtluxe.backend.controller;

import com.lichtluxe.backend.dto.MessageResponse;
import com.lichtluxe.backend.entity.*;
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
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * PayPal payment gateway controller.
 * Handles order creation, capture, and automated confirmation email dispatch.
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

    private User getCurrentUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found: " + email));
    }

    @PostMapping("/create-order")
    public ResponseEntity<?> createOrder(@RequestBody(required = false) Map<String, Object> payload) {
        try {
            User user = getCurrentUser();
            BigDecimal total = BigDecimal.ZERO;
            List<Item> paypalItems = new ArrayList<>();

            if (payload != null && payload.containsKey("items") && payload.get("items") instanceof List) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> itemsList = (List<Map<String, Object>>) payload.get("items");
                for (Map<String, Object> itemData : itemsList) {
                    String name = String.valueOf(itemData.getOrDefault("name", "Product"));
                    Object priceObj = itemData.get("price");
                    BigDecimal price = priceObj instanceof Number
                            ? BigDecimal.valueOf(((Number) priceObj).doubleValue()).setScale(2, RoundingMode.HALF_UP)
                            : new BigDecimal(String.valueOf(priceObj)).setScale(2, RoundingMode.HALF_UP);
                    int qty = itemData.containsKey("quantity") ? ((Number) itemData.get("quantity")).intValue() : 1;

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
            } else {
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
                    .description("LICHT LUXE NATURALS Order — Made in LOVEWORLD");

            orderRequest.purchaseUnits(Collections.singletonList(purchaseUnit));
            orderRequest.applicationContext(new ApplicationContext()
                    .brandName("LICHT LUXE NATURALS")
                    .landingPage("BILLING")
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

            User user = getCurrentUser();
            BigDecimal total = BigDecimal.ZERO;

            Order order = Order.builder()
                    .orderNumber("LLN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                    .user(user)
                    .paypalOrderId(paypalOrderId)
                    .paymentMethod("PAYPAL")
                    .status(Order.OrderStatus.CONFIRMED)
                    .lastNotifiedStatus(Order.OrderStatus.CONFIRMED)
                    .build();

            if (payload != null && payload.containsKey("shippingInfo") && payload.get("shippingInfo") instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, String> sInfo = (Map<String, String>) payload.get("shippingInfo");
                order.setShippingName(sInfo.get("shippingName"));
                order.setShippingAddress(sInfo.get("shippingAddress"));
                order.setShippingCity(sInfo.get("shippingCity"));
                order.setShippingPostcode(sInfo.get("shippingPostcode"));
                order.setShippingPhone(sInfo.get("shippingPhone"));
            } else {
                order.setShippingName(user.getFirstName() + " " + user.getLastName());
                order.setShippingPhone(user.getPhone());
            }

            if (payload != null && payload.containsKey("items") && payload.get("items") instanceof List) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> itemsList = (List<Map<String, Object>>) payload.get("items");
                for (Map<String, Object> itemData : itemsList) {
                    Long prodId = itemData.containsKey("id") ? ((Number) itemData.get("id")).longValue() : null;
                    String prodName = String.valueOf(itemData.getOrDefault("name", "Product"));
                    Object priceObj = itemData.get("price");
                    BigDecimal price = priceObj instanceof Number
                            ? BigDecimal.valueOf(((Number) priceObj).doubleValue()).setScale(2, RoundingMode.HALF_UP)
                            : new BigDecimal(String.valueOf(priceObj)).setScale(2, RoundingMode.HALF_UP);
                    int qty = itemData.containsKey("quantity") ? ((Number) itemData.get("quantity")).intValue() : 1;

                    Product product = null;
                    if (prodId != null) {
                        product = productRepository.findById(prodId).orElse(null);
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
                }
            }

            if (order.getItems().isEmpty()) {
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

            order.setTotalAmount(total.setScale(2, RoundingMode.HALF_UP));
            orderRepository.save(order);

            // 3. Automated Order Confirmation Email triggered from confirmed DB state
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
