package com.lichtluxe.backend.controller;

import com.lichtluxe.backend.dto.*;
import com.lichtluxe.backend.entity.PasswordResetOtp;
import com.lichtluxe.backend.entity.User;
import com.lichtluxe.backend.repository.PasswordResetOtpRepository;
import com.lichtluxe.backend.repository.UserRepository;
import com.lichtluxe.backend.security.JwtUtils;
import com.lichtluxe.backend.security.UserDetailsImpl;
import com.lichtluxe.backend.service.EmailService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Optional;

@CrossOrigin(origins = "*", maxAge = 3600)
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private AuthenticationManager authenticationManager;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordResetOtpRepository otpRepository;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private EmailService emailService;

    private static final SecureRandom RANDOM = new SecureRandom();

    @PostMapping("/login")
    public ResponseEntity<?> authenticateUser(@Valid @RequestBody LoginRequest loginRequest) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(loginRequest.getEmail(), loginRequest.getPassword()));

        SecurityContextHolder.getContext().setAuthentication(authentication);
        String jwt = jwtUtils.generateJwtToken(authentication);

        UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
        String role = userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .findFirst()
                .orElse("ROLE_CUSTOMER");

        User user = userRepository.findByEmail(userDetails.getEmail()).orElseThrow();

        return ResponseEntity.ok(new JwtResponse(jwt,
                userDetails.getId(),
                userDetails.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                role));
    }

    @PostMapping("/register")
    public ResponseEntity<?> registerUser(@Valid @RequestBody SignupRequest signUpRequest) {
        if (userRepository.existsByEmail(signUpRequest.getEmail())) {
            return ResponseEntity
                    .badRequest()
                    .body(new MessageResponse("Error: Email is already registered! Please sign in or reset your password."));
        }

        // Create new user's account
        User user = User.builder()
                .firstName(signUpRequest.getFirstName())
                .lastName(signUpRequest.getLastName())
                .email(signUpRequest.getEmail())
                .phone(signUpRequest.getPhone())
                .password(encoder.encode(signUpRequest.getPassword()))
                .role(User.Role.CUSTOMER)
                .build();

        userRepository.save(user);

        // 1. Trigger Automated Welcome Email to customer's registered email
        try {
            emailService.sendWelcomeEmail(user);
        } catch (Exception ex) {
            // Logged inside email service; do not block registration
        }

        return ResponseEntity.ok(new MessageResponse("User registered successfully! Welcome email sent."));
    }

    // ─── 2. Forgot Password — Request OTP ───────────────────────────────
    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        Optional<User> userOpt = userRepository.findByEmail(request.getEmail());
        if (userOpt.isEmpty()) {
            // For security, do not disclose whether user exists or not
            return ResponseEntity.ok(new MessageResponse("If an account exists with this email, a verification code has been dispatched."));
        }

        User user = userOpt.get();

        // Invalidate any older unused OTPs for this email
        otpRepository.findByEmail(user.getEmail()).forEach(oldOtp -> {
            oldOtp.setUsed(true);
            otpRepository.save(oldOtp);
        });

        // Generate 6-digit numeric OTP
        int code = 100000 + RANDOM.nextInt(900000);
        String otpCode = String.valueOf(code);

        PasswordResetOtp otp = PasswordResetOtp.builder()
                .email(user.getEmail())
                .otpCode(otpCode)
                .expiryTime(LocalDateTime.now().plusMinutes(10)) // 10 minutes expiry
                .attempts(0)
                .maxAttempts(5) // Max 5 attempts
                .used(false)
                .build();

        otpRepository.save(otp);

        // Dispatch OTP via Titan SMTP
        emailService.sendPasswordResetOtp(user.getEmail(), user.getFirstName(), otpCode, 10);

        return ResponseEntity.ok(new MessageResponse("A 6-digit verification code has been sent to your registered email address."));
    }

    // ─── 2b. Verify OTP ─────────────────────────────────────────────────
    @PostMapping("/verify-otp")
    public ResponseEntity<?> verifyOtp(@Valid @RequestBody VerifyOtpRequest request) {
        Optional<PasswordResetOtp> otpOpt = otpRepository.findTopByEmailAndUsedFalseOrderByCreatedAtDesc(request.getEmail());

        if (otpOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(new MessageResponse("No active reset request found for this email. Please request a new code."));
        }

        PasswordResetOtp otp = otpOpt.get();

        if (otp.isExpired()) {
            otp.setUsed(true);
            otpRepository.save(otp);
            return ResponseEntity.badRequest().body(new MessageResponse("The verification code has expired. Please request a new one."));
        }

        if (otp.isLockedOut()) {
            otp.setUsed(true);
            otpRepository.save(otp);
            return ResponseEntity.badRequest().body(new MessageResponse("Too many incorrect attempts. For security, this code has been locked. Please request a new code."));
        }

        if (!otp.getOtpCode().equals(request.getOtp().trim())) {
            otp.setAttempts(otp.getAttempts() + 1);
            otpRepository.save(otp);
            int remaining = otp.getMaxAttempts() - otp.getAttempts();
            return ResponseEntity.badRequest().body(new MessageResponse("Incorrect verification code. " + remaining + " attempt(s) remaining."));
        }

        return ResponseEntity.ok(new MessageResponse("OTP verified successfully. You may now reset your password."));
    }

    // ─── 2c. Reset Password after OTP Verification ─────────────────────
    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        Optional<PasswordResetOtp> otpOpt = otpRepository.findTopByEmailAndUsedFalseOrderByCreatedAtDesc(request.getEmail());

        if (otpOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(new MessageResponse("Invalid or expired reset session. Please request a new code."));
        }

        PasswordResetOtp otp = otpOpt.get();

        if (!otp.isValid(request.getOtp().trim())) {
            return ResponseEntity.badRequest().body(new MessageResponse("Invalid or expired code. Please verify again."));
        }

        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Update password with BCrypt
        user.setPassword(encoder.encode(request.getNewPassword()));
        userRepository.save(user);

        // Invalidate OTP
        otp.setUsed(true);
        otpRepository.save(otp);

        // Send confirmation email
        emailService.sendPasswordChangedEmail(user);

        return ResponseEntity.ok(new MessageResponse("Password has been reset successfully! You can now sign in with your new password."));
    }
}
