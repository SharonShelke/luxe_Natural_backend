package com.lichtluxe.backend.repository;

import com.lichtluxe.backend.entity.Cart;
import com.lichtluxe.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CartRepository extends JpaRepository<Cart, Long> {
    Optional<Cart> findByUser(User user);
}
