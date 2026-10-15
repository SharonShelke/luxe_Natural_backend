package com.lichtluxe.backend.controller;

import com.lichtluxe.backend.dto.MessageResponse;
import com.lichtluxe.backend.entity.ContactMessage;
import com.lichtluxe.backend.repository.ContactMessageRepository;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@CrossOrigin(origins = "*", maxAge = 3600)
@RestController
@RequestMapping("/api/contact")
public class ContactController {

    @Autowired
    private ContactMessageRepository contactMessageRepository;

    @PostMapping
    public ResponseEntity<?> submitContactForm(@Valid @RequestBody ContactMessage message) {
        contactMessageRepository.save(message);
        return ResponseEntity.ok(new MessageResponse("Thank you! Your message has been sent."));
    }
}
