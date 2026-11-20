package com.lichtluxe.backend.repository;

import com.lichtluxe.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    Optional<User> findByEmailIgnoreCase(String email);
    Optional<User> findByPhone(String phone);
    Optional<User> findByKingschatId(String kingschatId);
    Boolean existsByEmail(String email);
    Boolean existsByEmailIgnoreCase(String email);
    Boolean existsByPhone(String phone);
    Boolean existsByKingschatId(String kingschatId);
}
