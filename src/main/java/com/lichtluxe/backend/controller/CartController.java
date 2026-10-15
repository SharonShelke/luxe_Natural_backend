package com.lichtluxe.backend.controller;

import com.lichtluxe.backend.dto.CartItemRequest;
import com.lichtluxe.backend.dto.MessageResponse;
import com.lichtluxe.backend.entity.Cart;
import com.lichtluxe.backend.entity.CartItem;
import com.lichtluxe.backend.entity.Product;
import com.lichtluxe.backend.entity.User;
import com.lichtluxe.backend.repository.CartRepository;
import com.lichtluxe.backend.repository.ProductRepository;
import com.lichtluxe.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@CrossOrigin(origins = "*", maxAge = 3600)
@RestController
@RequestMapping("/api/cart")
public class CartController {

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    private User getCurrentUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email).orElseThrow(() -> new RuntimeException("User not found"));
    }

    @GetMapping
    public ResponseEntity<Cart> getCart() {
        User user = getCurrentUser();
        Cart cart = cartRepository.findByUser(user).orElseGet(() -> {
            Cart newCart = Cart.builder().user(user).build();
            return cartRepository.save(newCart);
        });
        return ResponseEntity.ok(cart);
    }

    @PostMapping("/add")
    public ResponseEntity<?> addToCart(@RequestBody CartItemRequest request) {
        User user = getCurrentUser();
        Cart cart = cartRepository.findByUser(user).orElseGet(() -> Cart.builder().user(user).build());

        Product product = productRepository.findById(request.getProductId())
                .orElseThrow(() -> new RuntimeException("Product not found"));

        Optional<CartItem> existingItem = cart.getItems().stream()
                .filter(item -> item.getProduct().getId().equals(product.getId()))
                .findFirst();

        if (existingItem.isPresent()) {
            existingItem.get().setQuantity(existingItem.get().getQuantity() + request.getQuantity());
        } else {
            CartItem newItem = CartItem.builder()
                    .product(product)
                    .quantity(request.getQuantity())
                    .unitPrice(product.getPrice())
                    .build();
            cart.addItem(newItem);
        }

        cartRepository.save(cart);
        return ResponseEntity.ok(new MessageResponse("Product added to cart"));
    }
}
