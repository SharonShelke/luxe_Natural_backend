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
import org.springframework.http.MediaType;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@CrossOrigin(origins = "*", maxAge = 3600)
@RestController
@RequestMapping({"/api/auth", "/auth"})
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

    @Value("${app.kingschat.client-id:5510380c-caac-4baa-ad0c-288dcdffaf1f}")
    private String kingsChatClientId;

    @Value("${app.kingschat.api-key:bGMvMYE5ha5NBApyUgsJ2CXzA3PyUVXmRKUtzLxlKGw=}")
    private String kingsChatApiKey;

    @Value("${app.kingschat.redirect-url:https://lichtluxenaturals.org/}")
    private String kingsChatRedirectUrl;

    @Value("${app.kingschat.connect-url:https://connect.kingsch.at}")
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

    @PostMapping(value = "/kingschat/callback", consumes = {
            MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            MediaType.APPLICATION_JSON_VALUE,
            MediaType.ALL_VALUE
    })
    public ResponseEntity<?> handleKingsChatCallback(
            @RequestParam(required = false) Map<String, String> formParams,
            @RequestBody(required = false) Map<String, Object> bodyParams,
            HttpServletResponse response) throws IOException {

        Map<String, Object> payload = new HashMap<>();
        if (formParams != null) {
            payload.putAll(formParams);
        }
        if (bodyParams != null) {
            payload.putAll(bodyParams);
        }

        String origin = String.valueOf(payload.getOrDefault("origin", ""));
        if ("kingschat-redirect-url-test".equalsIgnoreCase(origin)) {
            log.info("KingsChat Developer Portal Redirect URL test verified successfully.");
            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "origin", origin,
                    "message", "KingsChat Redirect URL test passed successfully!"
            ));
        }

        try {
            ResponseEntity<?> loginResult = loginWithKingsChat(payload);
            if (loginResult.getStatusCode().is2xxSuccessful() && loginResult.getBody() instanceof JwtResponse) {
                JwtResponse jwt = (JwtResponse) loginResult.getBody();
                response.sendRedirect(kingsChatRedirectUrl + "?token=" + jwt.getToken());
                return null;
            }
        } catch (Exception ex) {
            log.error("KingsChat form callback error: {}", ex.getMessage());
        }

        String code = String.valueOf(payload.getOrDefault("code", ""));
        String token = String.valueOf(payload.getOrDefault("token", payload.getOrDefault("accessToken", "")));
        if (!code.isBlank()) {
            response.sendRedirect(kingsChatRedirectUrl + "?code=" + URLEncoder.encode(code, StandardCharsets.UTF_8));
            return null;
        } else if (!token.isBlank()) {
            response.sendRedirect(kingsChatRedirectUrl + "?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8));
            return null;
        }

        response.sendRedirect(kingsChatRedirectUrl);
        return null;
    }

    @PostMapping({"/kingchat", "/kingschat"})
    public ResponseEntity<?> kingchatLogin(@RequestBody Map<String, Object> request) {
        return loginWithKingsChat(request);
    }

    public ResponseEntity<?> loginWithKingsChat(Map<String, Object> request) {
        try {
            String token = request.get("token") != null ? String.valueOf(request.get("token")) : null;
            if (token == null && request.get("accessToken") != null) {
                token = String.valueOf(request.get("accessToken"));
            }
            if (token == null && request.get("access_token") != null) {
                token = String.valueOf(request.get("access_token"));
            }
            if (token == null && request.get("code") != null) {
                token = String.valueOf(request.get("code"));
            }

            Object emailObj = request.get("email");
            String email = null;
            if (emailObj instanceof Map) {
                Object addr = ((Map<?, ?>) emailObj).get("address");
                if (addr == null) addr = ((Map<?, ?>) emailObj).get("email");
                email = addr != null ? String.valueOf(addr) : null;
            } else if (emailObj != null) {
                email = String.valueOf(emailObj);
            }
            if (email != null && (email.isBlank() || "null".equalsIgnoreCase(email.trim()))) {
                email = null;
            }

            String firstName = request.get("firstName") != null ? String.valueOf(request.get("firstName")) : null;
            if (firstName == null && request.get("first_name") != null) {
                firstName = String.valueOf(request.get("first_name"));
            }
            String lastName = request.get("lastName") != null ? String.valueOf(request.get("lastName")) : null;
            if (lastName == null && request.get("last_name") != null) {
                lastName = String.valueOf(request.get("last_name"));
            }
            String username = request.get("username") != null ? String.valueOf(request.get("username")) : null;
            String phone = request.get("phone") != null ? String.valueOf(request.get("phone")) : null;

            return ResponseEntity.ok(loginWithKingChatToken(token, email, firstName, lastName, username, phone));
        } catch (RuntimeException e) {
            log.error("KingChat login error: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(new MessageResponse(e.getMessage()));
        }
    }

    public JwtResponse loginWithKingChatToken(String token, String email, String firstName, String lastName, String username, String phone) {
        if (token == null || token.isBlank()) {
            throw new RuntimeException("Invalid KingsChat token");
        }

        String kcId = (username != null && !username.isBlank()) ? username : null;

        // 0. Pre-decode JWT to extract sub, email, and names immediately as fast local fallback
        try {
            String[] chunks = token.split("\\.");
            if (chunks.length >= 2) {
                java.util.Base64.Decoder decoder = java.util.Base64.getUrlDecoder();
                String payload = new String(decoder.decode(chunks[1]), StandardCharsets.UTF_8);
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                Map<String, Object> claims = mapper.readValue(payload, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});

                if (kcId == null && claims.containsKey("sub")) kcId = String.valueOf(claims.get("sub"));
                if (email == null && claims.containsKey("email")) {
                    Object claimsEmail = claims.get("email");
                    if (claimsEmail instanceof Map) {
                        Object addr = ((Map<?, ?>) claimsEmail).get("address");
                        email = addr != null ? String.valueOf(addr) : null;
                    } else if (claimsEmail != null) {
                        email = String.valueOf(claimsEmail);
                    }
                }
                if (firstName == null && claims.containsKey("first_name")) firstName = String.valueOf(claims.get("first_name"));
                if (lastName == null && claims.containsKey("last_name")) lastName = String.valueOf(claims.get("last_name"));
                if (username == null && claims.containsKey("username")) username = String.valueOf(claims.get("username"));
                if (phone == null && claims.containsKey("phone_number")) phone = String.valueOf(claims.get("phone_number"));

                log.info("KingsChat pre-decoded JWT: sub={}, email={}, username={}", kcId, email, username);
            }
        } catch (Exception e) {
            log.warn("KingsChat initial JWT decode note: {}", e.getMessage());
        }

        // 1. Try fetching from KingsChat API for verification and profile updates if email or kcId is missing
        if (kcId == null || email == null) {
            try {
                org.springframework.web.client.RestTemplate restTemplate = new org.springframework.web.client.RestTemplate();
                org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
                headers.setBearerAuth(token);
                if (kingsChatApiKey != null && !kingsChatApiKey.isBlank()) {
                    headers.set("x-api-key", kingsChatApiKey);
                }
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

                    if (userMap.get("email") != null) {
                        Object pEmail = userMap.get("email");
                        if (pEmail instanceof Map) {
                            Object addr = ((Map<?, ?>) pEmail).get("address");
                            email = addr != null ? String.valueOf(addr) : null;
                        } else {
                            email = String.valueOf(pEmail);
                        }
                    }
                    if (username == null && userMap.get("username") != null) username = String.valueOf(userMap.get("username"));
                    if (kcId == null && userMap.get("id") != null) kcId = String.valueOf(userMap.get("id"));
                    if (kcId == null && userMap.get("sub") != null) kcId = String.valueOf(userMap.get("sub"));
                    if (firstName == null && userMap.get("first_name") != null) firstName = String.valueOf(userMap.get("first_name"));
                    if (lastName == null && userMap.get("last_name") != null) lastName = String.valueOf(userMap.get("last_name"));
                    if (userMap.get("name") != null && (firstName == null || firstName.isBlank())) {
                        String fullName = String.valueOf(userMap.get("name"));
                        String[] parts = fullName.trim().split("\\s+", 2);
                        firstName = parts[0];
                        if (parts.length > 1) lastName = parts[1];
                    }
                    if (phone == null && userMap.get("phone_number") != null) phone = String.valueOf(userMap.get("phone_number"));
                }
            } catch (Exception ex) {
                log.warn("KingsChat Profile API query note: {}", ex.getMessage());
            }
        }

        if (kcId == null && username != null) {
            kcId = username;
        }

        if (email == null || email.isBlank() || "null".equalsIgnoreCase(email.trim())) {
            email = (username != null && !username.isBlank())
                    ? username.replaceAll("[^a-zA-Z0-9._-]", "") + "@kingschat.loveworld.org"
                    : ((kcId != null && !kcId.isBlank()) ? kcId + "@kingschat.loveworld.org" : null);
        }

        if (email == null) {
            throw new RuntimeException("Authentication Failed: KingsChat identity could not be verified.");
        }

        email = email.trim().toLowerCase();

        if (firstName == null || firstName.trim().isBlank()) {
            if (username != null && !username.trim().isBlank()) {
                firstName = username;
                lastName = (lastName != null && !lastName.isBlank()) ? lastName : "";
            } else if (email.contains("@")) {
                firstName = email.substring(0, email.indexOf("@"));
                lastName = (lastName != null && !lastName.isBlank()) ? lastName : "";
            } else {
                firstName = "KingsChat";
                lastName = "Member";
            }
        }
        if (lastName == null) {
            lastName = "";
        }

        // Lookup in database:
        // 1. By KingsChat ID
        Optional<User> userOpt = Optional.empty();
        if (kcId != null && !kcId.isBlank()) {
            userOpt = userRepository.findByKingschatId(kcId);
        }
        // 2. By Email
        if (userOpt.isEmpty()) {
            userOpt = userRepository.findByEmailIgnoreCase(email);
        }
        // 3. By Phone
        if (userOpt.isEmpty() && phone != null && !phone.isBlank()) {
            userOpt = userRepository.findByPhone(phone);
        }

        boolean isSharonAdmin = email.equalsIgnoreCase("sharonshelke1@gmail.com")
                || email.equalsIgnoreCase("sharon@lichtluxenaturals.org")
                || (username != null && username.toLowerCase().contains("sharon"));

        User user;
        if (userOpt.isPresent()) {
            user = userOpt.get();
            boolean needsSave = false;
            if (isSharonAdmin && user.getRole() != User.Role.ADMIN) {
                user.setRole(User.Role.ADMIN);
                needsSave = true;
            }
            if (kcId != null && !kcId.isBlank() && (user.getKingschatId() == null || !user.getKingschatId().equals(kcId))) {
                user.setKingschatId(kcId);
                needsSave = true;
            }
            if (phone != null && !phone.isBlank() && (user.getPhone() == null || user.getPhone().isBlank())) {
                user.setPhone(phone);
                needsSave = true;
            }
            if ((user.getFirstName() == null || user.getFirstName().isBlank() || "Customer".equals(user.getFirstName()))
                    && firstName != null && !firstName.isBlank()) {
                user.setFirstName(firstName);
                if (lastName != null) user.setLastName(lastName);
                needsSave = true;
            }
            user.setKingchatLoginCount((user.getKingchatLoginCount() != null ? user.getKingchatLoginCount() : 0) + 1);
            needsSave = true;

            if (needsSave) {
                user = userRepository.save(user);
            }
        } else {
            user = User.builder()
                    .firstName(firstName)
                    .lastName(lastName != null ? lastName : "")
                    .email(email)
                    .phone(phone != null && !phone.isBlank() ? phone : null)
                    .kingschatId(kcId)
                    .kingchatLoginCount(1)
                    .password(encoder.encode(java.util.UUID.randomUUID().toString()))
                    .role(isSharonAdmin ? User.Role.ADMIN : User.Role.CUSTOMER)
                    .isActive(true)
                    .build();
            user = userRepository.save(user);

            try {
                emailService.sendWelcomeEmail(user);
            } catch (Exception ignored) {}
        }

        // Generate JWT Token
        String jwt = jwtUtils.generateTokenFromEmail(user.getEmail());

        return new JwtResponse(
                jwt,
                user.getId(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                user.getRole() != null ? user.getRole().name() : "ROLE_CUSTOMER"
        );
    }
}
