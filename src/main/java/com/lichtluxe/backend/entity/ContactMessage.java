package com.lichtluxe.backend.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Contact form submission entity.
 */
@Entity
@Table(name = "contact_messages")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class ContactMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Size(max = 100)
    private String name;

    @NotBlank
    @Email
    @Size(max = 150)
    private String email;

    @Size(max = 20)
    private String phone;

    @Size(max = 100)
    @Builder.Default
    private String topic = "General Inquiry";

    @NotBlank
    @Column(columnDefinition = "TEXT")
    private String message;

    @Column(name = "is_read")
    @Builder.Default
    private boolean isRead = false;

    @Column(name = "created_at", updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
