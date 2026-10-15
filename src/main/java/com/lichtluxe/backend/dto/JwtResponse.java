package com.lichtluxe.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class JwtResponse {
    private String token;
    private String type = "Bearer";
    private Long id;
    private String email;
    private String firstName;
    private String lastName;
    private String role;

    public JwtResponse(String accessToken, Long id, String email, String firstName, String lastName, String role) {
        this.token = accessToken;
        this.id = id;
        this.email = email;
        this.firstName = firstName;
        this.lastName = lastName;
        this.role = role;
    }
}
