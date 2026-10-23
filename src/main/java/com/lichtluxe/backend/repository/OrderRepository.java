package com.lichtluxe.backend.repository;

import com.lichtluxe.backend.entity.Order;
import com.lichtluxe.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {
    List<Order> findByUserOrderByCreatedAtDesc(User user);
    Optional<Order> findByOrderNumber(String orderNumber);
    Optional<Order> findByPaypalOrderId(String paypalOrderId);
    Boolean existsByPaypalOrderId(String paypalOrderId);
}
