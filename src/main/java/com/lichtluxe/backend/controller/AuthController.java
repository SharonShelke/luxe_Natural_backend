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
import java.util.*;
import org.springframework.beans.factory.annotation.Value;

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

    @Value("${app.kingschat.project-id:c4492f85-ffcf-46ad-97d4-4aa7fa0de03b}")
    private String kingsChatProjectId;

    @Value("${app.kingschat.client-id:c4492f85-ffcf-46ad-97d4-4aa7fa0de03b}")
    private String kingsChatClientId;

    @Value("${app.kingschat.api-key:bGMvMYE5ha5NBApyUgsJ2CXzA3PyUVXmRKUtzLxlKGw=}")
    private String kingsChatApiKey;

    @Value("${app.kingschat.redirect-url:https://lichtluxenaturals.org/}")
    private String kingsChatRedirectUrl;

    @Value("${app.kingschat.connect-url:https://connect.kingschat.online}")
    private String kingsChatConnectUrl;

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AuthController.class);

    @PostMapping("/login")
    public ResponseEntity<?> authenticateUser(@Valid @RequestBody LoginRequest loginRequest) {
        if (loginRequest == null || loginRequest.getEmail() == null || loginRequest.getPassword() == null) {
            return ResponseEntity.badRequest().body(new MessageResponse("Email and password are required."));
        }
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(loginRequest.getEmail().trim().toLowerCase(), loginRequest.getPassword()));

            SecurityContextHolder.getContext().setAuthentication(authentication);
            String jwt = jwtUtils.generateJwtToken(authentication);

            UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
            String role = userDetails.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .findFirst()
                    .orElse("ROLE_CUSTOMER");

            User user = userRepository.findByEmail(userDetails.getEmail())
                    .orElseThrow(() -> new com.lichtluxe.backend.exception.UnauthorizedException("User account not found."));

            return ResponseEntity.ok(new JwtResponse(jwt,
                    userDetails.getId(),
                    userDetails.getEmail(),
                    user.getFirstName(),
                    user.getLastName(),
                    role));
        } catch (org.springframework.security.authentication.BadCredentialsException e) {
            log.warn("Failed login attempt for: {}", loginRequest.getEmail());
            return ResponseEntity.status(org.springframework.http.HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse("Invalid email or password. Please verify credentials."));
        } catch (org.springframework.security.authentication.DisabledException | org.springframework.security.authentication.LockedException e) {
            log.warn("Blocked login for disabled/locked user: {}", loginRequest.getEmail());
            return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN)
                    .body(new MessageResponse("Account is locked or disabled. Please contact customer support."));
        } catch (Exception e) {
            log.error("Login unexpected error: {}", e.getMessage(), e);
            return ResponseEntity.status(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new MessageResponse("An error occurred during authentication. Please try again."));
        }
    }

    @PostMapping("/register")
    public ResponseEntity<?> registerUser(@Valid @RequestBody SignupRequest signUpRequest) {
        String cleanEmail = signUpRequest.getEmail().trim().toLowerCase();
        if (userRepository.existsByEmailIgnoreCase(cleanEmail)) {
            return ResponseEntity
                    .badRequest()
                    .body(new MessageResponse("Error: An account with this email address already exists! Please sign in."));
        }

        if (signUpRequest.getPhone() != null && !signUpRequest.getPhone().trim().isBlank()) {
            String cleanPhone = signUpRequest.getPhone().trim();
            if (userRepository.existsByPhone(cleanPhone)) {
                return ResponseEntity
                        .badRequest()
                        .body(new MessageResponse("Error: An account with this phone number already exists! Please sign in."));
            }
        }

        // Create new user's account
        User user = User.builder()
                .firstName(signUpRequest.getFirstName())
                .lastName(signUpRequest.getLastName())
                .email(cleanEmail)
                .phone(signUpRequest.getPhone() != null ? signUpRequest.getPhone().trim() : null)
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

    // ─── 3. KingsChat SSO Integration ──────────────────────────────────
    @GetMapping("/kingschat/config")
    public ResponseEntity<?> getKingsChatConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("projectId", kingsChatProjectId);
        config.put("clientId", kingsChatClientId);
        config.put("scopes", List.of("profile"));
        config.put("redirectUri", kingsChatRedirectUrl);
        config.put("connectUrl", kingsChatConnectUrl);
        return ResponseEntity.ok(config);
    }

    @PostMapping("/kingschat")
    public ResponseEntity<?> loginWithKingsChat(@RequestBody Map<String, Object> payload) {
        try {
            String email = null;
            String name = null;
            String username = null;
            String phone = null;
            String avatarUrl = null;

            // 1. If accessToken provided, query KingsChat Profile API
            String accessToken = payload.containsKey("accessToken") && payload.get("accessToken") != null
                    ? String.valueOf(payload.get("accessToken")) : null;

            if (accessToken != null && !accessToken.isBlank()) {
                try {
                    org.springframework.web.client.RestTemplate restTemplate = new org.springframework.web.client.RestTemplate();
                    org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
                    headers.setBearerAuth(accessToken);
                    headers.set("x-api-key", kingsChatApiKey);
                    org.springframework.http.HttpEntity<?> entity = new org.springframework.http.HttpEntity<>(headers);

                    String profileUrl = kingsChatConnectUrl + "/api/profile";
                    org.springframework.http.ResponseEntity<Map> resp = restTemplate.exchange(
                            profileUrl, org.springframework.http.HttpMethod.GET, entity, Map.class);

                    if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                        Map<?, ?> body = resp.getBody();
                        Map<?, ?> profile = body.containsKey("profile") && body.get("profile") instanceof Map
                                ? (Map<?, ?>) body.get("profile") : body;
                        Map<?, ?> userMap = profile.containsKey("user") && profile.get("user") instanceof Map
                                ? (Map<?, ?>) profile.get("user") : profile;

                        if (userMap.get("email") != null) email = String.valueOf(userMap.get("email"));
                        if (userMap.get("name") != null) name = String.valueOf(userMap.get("name"));
                        if (userMap.get("username") != null) username = String.valueOf(userMap.get("username"));
                        if (userMap.get("phone_number") != null) phone = String.valueOf(userMap.get("phone_number"));
                        if (userMap.get("avatar_url") != null) avatarUrl = String.valueOf(userMap.get("avatar_url"));
                    }
                } catch (Exception ex) {
                    log.warn("KingsChat Profile API query note: {}", ex.getMessage());
                }
            }

            // 2. Direct profile payload fallback
            if (payload.containsKey("profile") && payload.get("profile") instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> p = (Map<String, Object>) payload.get("profile");
                if (email == null && p.get("email") != null) email = String.valueOf(p.get("email"));
                if (name == null && p.get("name") != null) name = String.valueOf(p.get("name"));
                if (username == null && p.get("username") != null) username = String.valueOf(p.get("username"));
                if (phone == null && p.get("phone") != null) phone = String.valueOf(p.get("phone"));
                if (avatarUrl == null && p.get("avatarUrl") != null) avatarUrl = String.valueOf(p.get("avatarUrl"));
            }

            // Also check top-level fields
            if (email == null && payload.get("email") != null) email = String.valueOf(payload.get("email"));
            if (name == null && payload.get("name") != null) name = String.valueOf(payload.get("name"));
            if (username == null && payload.get("username") != null) username = String.valueOf(payload.get("username"));

            if ((email == null || email.isBlank()) && (username == null || username.isBlank())) {
                return ResponseEntity.badRequest().body(new MessageResponse("KingsChat profile authentication could not resolve user identity."));
            }

            String finalEmail = (email != null && !email.isBlank())
                    ? email.trim().toLowerCase()
                    : (username.replaceAll("[^a-zA-Z0-9._-]", "") + "@kingschat.loveworld.org").toLowerCase();

            String firstName = "KingsChat";
            String lastName = "Member";
            if (name != null && !name.isBlank()) {
                String[] parts = name.trim().split("\\s+", 2);
                firstName = parts[0];
                if (parts.length > 1) lastName = parts[1];
            } else if (username != null && !username.isBlank()) {
                firstName = username;
            }

            // Check if user exists
            Optional<User> existingUserOpt = userRepository.findByEmail(finalEmail);
            User user;
            if (existingUserOpt.isPresent()) {
                user = existingUserOpt.get();
                if (user.getFirstName() == null || user.getFirstName().isBlank() || "Customer".equals(user.getFirstName())) {
                    user.setFirstName(firstName);
                    user.setLastName(lastName);
                    userRepository.save(user);
                }
            } else {
                // Register new user via KingsChat SSO
                user = User.builder()
                        .firstName(firstName)
                        .lastName(lastName)
                        .email(finalEmail)
                        .phone(phone != null && !phone.isBlank() ? phone : null)
                        .password(encoder.encode(java.util.UUID.randomUUID().toString()))
                        .role(User.Role.CUSTOMER)
                        .isActive(true)
                        .build();
                user = userRepository.save(user);

                try {
                    emailService.sendWelcomeEmail(user);
                } catch (Exception ignored) {}
            }

            // Generate JWT Token
            String jwt = jwtUtils.generateTokenFromEmail(user.getEmail());

            return ResponseEntity.ok(new JwtResponse(
                    jwt,
                    user.getId(),
                    user.getEmail(),
                    user.getFirstName(),
                    user.getLastName(),
                    user.getRole() != null ? user.getRole().name() : "ROLE_CUSTOMER"
            ));

        } catch (Exception e) {
            log.error("KingsChat authentication error", e);
            return ResponseEntity.status(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new MessageResponse("KingsChat authentication failed: " + e.getMessage()));
        }
    }
}
