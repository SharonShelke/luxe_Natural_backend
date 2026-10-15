package com.lichtluxe.backend.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * User entity for LICHT LUXE NATURALS customer accounts.
 */
@Entity
@Table(name = "users",
       uniqueConstraints = {
           @UniqueConstraint(columnNames = "email"),
           @UniqueConstraint(columnNames = "phone")
       })
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Size(max = 100)
    @Column(name = "first_name")
    private String firstName;

    @NotBlank
    @Size(max = 100)
    @Column(name = "last_name")
    private String lastName;

    @NotBlank
    @Email
    @Size(max = 150)
    @Column(unique = true)
    private String email;

    @Size(max = 20)
    @Column(unique = true)
    private String phone;

    @NotBlank
    @Size(max = 255)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    @Builder.Default
    private Role role = Role.CUSTOMER;

    @Column(name = "created_at", updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @Column(name = "is_active")
    @Builder.Default
    private boolean isActive = true;

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public enum Role {
        CUSTOMER,
        ADMIN
    }
}
