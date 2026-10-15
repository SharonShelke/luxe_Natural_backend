package com.lichtluxe.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SignupRequest {
    @NotBlank
    @Size(max = 100)
    private String firstName;

    @NotBlank
    @Size(max = 100)
    private String lastName;

    @NotBlank
    @Email
    private String email;

    @Size(max = 20)
    private String phone;

    @NotBlank
    @Size(min = 6, max = 40)
    private String password;
}
