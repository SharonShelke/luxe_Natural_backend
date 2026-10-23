package com.lichtluxe.backend.controller;

import com.lichtluxe.backend.dto.MessageResponse;
import com.lichtluxe.backend.dto.OrderRequest;
import com.lichtluxe.backend.dto.OrderStatusUpdateRequest;
import com.lichtluxe.backend.entity.*;
import com.lichtluxe.backend.repository.CartRepository;
import com.lichtluxe.backend.repository.OrderRepository;
import com.lichtluxe.backend.repository.UserRepository;
import com.lichtluxe.backend.service.EmailService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

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
    private EmailService emailService;

    private User getCurrentUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email).orElseThrow(() -> new RuntimeException("User not found"));
    }

    @GetMapping
    public List<Order> getUserOrders() {
        return orderRepository.findByUserOrderByCreatedAtDesc(getCurrentUser());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getOrderById(@PathVariable Long id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Order not found with id: " + id));
        return ResponseEntity.ok(order);
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

        // 3. Automated Order Confirmation Email
        emailService.sendOrderConfirmationEmail(order);

        // Clear cart
        cart.getItems().clear();
        cartRepository.save(cart);

        return ResponseEntity.ok(order);
    }

    // ─── 4. Order Status Update & Notification (Admin / System Trigger) ───
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

        // Only send notification if status changed and hasn't been notified yet to avoid duplicate emails
        if (order.getLastNotifiedStatus() == null || !order.getLastNotifiedStatus().equals(newStatus)) {
            emailService.sendOrderStatusEmail(order, newStatus);
            order.setLastNotifiedStatus(newStatus);
        }

        orderRepository.save(order);

        return ResponseEntity.ok(order);
    }
}
