package com.lichtluxe.backend.controller;

import com.lichtluxe.backend.dto.MessageResponse;
import com.lichtluxe.backend.dto.OrderRequest;
import com.lichtluxe.backend.entity.*;
import com.lichtluxe.backend.repository.CartRepository;
import com.lichtluxe.backend.repository.OrderRepository;
import com.lichtluxe.backend.repository.UserRepository;
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

    private User getCurrentUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email).orElseThrow(() -> new RuntimeException("User not found"));
    }

    @GetMapping
    public List<Order> getUserOrders() {
        return orderRepository.findByUserOrderByCreatedAtDesc(getCurrentUser());
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
                .shippingName(request.getShippingName())
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
        
        // Clear cart
        cart.getItems().clear();
        cartRepository.save(cart);

        return ResponseEntity.ok(order);
    }
}
