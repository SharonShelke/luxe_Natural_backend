package com.lichtluxe.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lichtluxe.backend.entity.Order;
import com.lichtluxe.backend.entity.Product;
import com.lichtluxe.backend.entity.User;
import com.lichtluxe.backend.repository.OrderRepository;
import com.lichtluxe.backend.repository.ProductRepository;
import com.lichtluxe.backend.repository.UserRepository;
import com.lichtluxe.backend.security.JwtUtils;
import com.paypal.core.PayPalHttpClient;
import com.paypal.http.HttpResponse;
import com.paypal.orders.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class SecurityAndCheckoutIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private PayPalHttpClient payPalHttpClient;

    private String customerToken;
    private String adminToken;

    @BeforeEach
    public void setup() {
        // Clear orders and non-essential users
        orderRepository.deleteAll();

        // Ensure a test customer exists
        User customer = userRepository.findByEmail("customer.test@example.co.uk").orElseGet(() -> {
            return userRepository.save(User.builder()
                    .firstName("Test")
                    .lastName("Customer")
                    .email("customer.test@example.co.uk")
                    .password(passwordEncoder.encode("Password123!"))
                    .role(User.Role.CUSTOMER)
                    .isActive(true)
                    .build());
        });
        customerToken = jwtUtils.generateTokenFromEmail(customer.getEmail());

        // Ensure a test admin exists
        User admin = userRepository.findByEmail("admin.test@example.co.uk").orElseGet(() -> {
            return userRepository.save(User.builder()
                    .firstName("Test")
                    .lastName("Admin")
                    .email("admin.test@example.co.uk")
                    .password(passwordEncoder.encode("Password123!"))
                    .role(User.Role.ADMIN)
                    .isActive(true)
                    .build());
        });
        adminToken = jwtUtils.generateTokenFromEmail(admin.getEmail());

        // Ensure Pure Shea Butter exists with price 14.99 and stock 100
        Product sheaButter = productRepository.findBySlug("pure-shea-butter").orElseGet(() -> {
            return productRepository.save(Product.builder()
                    .slug("pure-shea-butter")
                    .name("Pure Shea Butter")
                    .price(new BigDecimal("14.99"))
                    .stockQuantity(100)
                    .isActive(true)
                    .category(Product.ProductCategory.BUTTER)
                    .build());
        });
        sheaButter.setPrice(new BigDecimal("14.99"));
        sheaButter.setStockQuantity(100);
        productRepository.save(sheaButter);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TASK 9(a): Admin endpoints without token return 401; with USER return 403; with ADMIN return 200
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/admin/orders without token must return 401")
    public void testGetAdminOrdersWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/admin/orders"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("GET /api/admin/users without token must return 401")
    public void testGetAdminUsersWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("GET /api/admin/orders with CUSTOMER token must return 403 Forbidden")
    public void testGetAdminOrdersWithCustomerTokenReturns403() throws Exception {
        mockMvc.perform(get("/api/admin/orders")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"));
    }

    @Test
    @DisplayName("GET /api/admin/users with CUSTOMER token must return 403 Forbidden")
    public void testGetAdminUsersWithCustomerTokenReturns403() throws Exception {
        mockMvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"));
    }

    @Test
    @DisplayName("GET /api/admin/orders with ADMIN token must return 200 OK")
    public void testGetAdminOrdersWithAdminTokenReturns200() throws Exception {
        mockMvc.perform(get("/api/admin/orders")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/admin/users with ADMIN token must return 200 OK")
    public void testGetAdminUsersWithAdminTokenReturns200() throws Exception {
        mockMvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TASK 9(b): POST /api/admin/users/promote without admin auth is rejected
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/admin/users/promote without token must return 401")
    public void testPromoteUserWithoutTokenReturns401() throws Exception {
        Map<String, String> payload = Map.of("email", "customer.test@example.co.uk");
        mockMvc.perform(post("/api/admin/users/promote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/admin/users/promote with CUSTOMER token must return 403")
    public void testPromoteUserWithCustomerTokenReturns403() throws Exception {
        Map<String, String> payload = Map.of("email", "customer.test@example.co.uk");
        mockMvc.perform(post("/api/admin/users/promote")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/admin/users/promote with ADMIN token succeeds with 200")
    public void testPromoteUserWithAdminTokenSucceeds() throws Exception {
        Map<String, String> payload = Map.of("email", "customer.test@example.co.uk");
        mockMvc.perform(post("/api/admin/users/promote")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk());
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TASK 9(c): Checkout with price "0.01" and total "0.01" still charges database price
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Checkout with client price 0.01 and total 0.01 ignores client prices and charges canonical DB price")
    public void testCheckoutWithTamperedPriceStillChargesDatabasePrice() throws Exception {
        // Expected calculation:
        // 1 * Pure Shea Butter = £14.99 (canonical DB price)
        // Standard delivery (< £70.00) = £6.99
        // Server Total = £14.99 + £6.99 = £21.98 GBP

        String mockPaypalOrderId = "PAYPAL-ORDER-TEST-001";

        // Mock PayPal SDK to return a COMPLETED order matching the canonical server total of 21.98 GBP
        com.paypal.orders.Order mockPpOrder = new com.paypal.orders.Order();
        mockPpOrder.status("COMPLETED");
        mockPpOrder.id(mockPaypalOrderId);

        PurchaseUnit pu = new PurchaseUnit();
        AmountWithBreakdown amt = new AmountWithBreakdown();
        amt.currencyCode("GBP");
        amt.value("21.98");
        pu.amountWithBreakdown(amt);
        mockPpOrder.purchaseUnits(List.of(pu));

        @SuppressWarnings("unchecked")
        HttpResponse<com.paypal.orders.Order> mockResponse = (HttpResponse<com.paypal.orders.Order>) mock(HttpResponse.class);
        when(mockResponse.result()).thenReturn(mockPpOrder);
        when(mockResponse.statusCode()).thenReturn(200);
        when(payPalHttpClient.execute(any(OrdersGetRequest.class))).thenReturn(mockResponse);

        // Malicious client payload attempting price tampering
        Map<String, Object> maliciousPayload = new LinkedHashMap<>();
        maliciousPayload.put("shippingInfo", Map.of(
                "shippingName", "Jane Doe",
                "shippingEmail", "jane.doe@example.co.uk",
                "shippingAddress", "10 Downing Street",
                "shippingCity", "London",
                "shippingPostcode", "SW1A 2AA"
        ));
        maliciousPayload.put("items", List.of(
                Map.of(
                        "id", "pure-shea-butter",
                        "name", "Pure Shea Butter",
                        "price", 0.01,         // <-- Tampered client unit price!
                        "quantity", 1
                )
        ));
        maliciousPayload.put("total", "0.01"); // <-- Tampered client order total!
        maliciousPayload.put("paymentMethod", "PayPal");
        maliciousPayload.put("paypalOrderId", mockPaypalOrderId);

        mockMvc.perform(post("/api/orders/checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(maliciousPayload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.total").value(21.98)) // Server enforced £21.98, NOT £0.01!
                .andExpect(jsonPath("$.subtotal").value(14.99))
                .andExpect(jsonPath("$.deliveryFee").value(6.99));

        // Verify database persistence integrity
        List<Order> orders = orderRepository.findAll();
        assertFalse(orders.isEmpty(), "Order should be saved in database");
        Order savedOrder = orders.get(0);
        assertEquals(new BigDecimal("21.98"), savedOrder.getTotalAmount(), "Database total amount must be £21.98");
        assertEquals(1, savedOrder.getItems().size());
        assertEquals(new BigDecimal("14.99"), savedOrder.getItems().get(0).getUnitPrice(), "Database unit price must be £14.99");
        assertEquals(new BigDecimal("14.99"), savedOrder.getItems().get(0).getLineTotal(), "Database line total must be £14.99");
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TASK 9(d): Checkout with quantity -5, 0 or "abc" returns 400
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Checkout with negative quantity (-5) returns 400 Bad Request")
    public void testCheckoutWithNegativeQuantityReturns400() throws Exception {
        Map<String, Object> payload = createCheckoutPayloadWithQuantity(-5);
        mockMvc.perform(post("/api/orders/checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("Checkout with zero quantity (0) returns 400 Bad Request")
    public void testCheckoutWithZeroQuantityReturns400() throws Exception {
        Map<String, Object> payload = createCheckoutPayloadWithQuantity(0);
        mockMvc.perform(post("/api/orders/checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("Checkout with non-numeric quantity ('abc') returns 400 without 500 error")
    public void testCheckoutWithNonNumericQuantityReturns400() throws Exception {
        Map<String, Object> payload = createCheckoutPayloadWithQuantity("abc");
        mockMvc.perform(post("/api/orders/checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TASK 9(e): Quantity above stock returns 400
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Checkout with quantity above available stock returns 400 Bad Request")
    public void testCheckoutWithQuantityAboveStockReturns400() throws Exception {
        // Shea butter stock is set to 5
        Product product = productRepository.findBySlug("pure-shea-butter").orElseThrow();
        product.setStockQuantity(5);
        productRepository.save(product);

        // Client requests 10 units (greater than available stock of 5)
        Map<String, Object> payload = createCheckoutPayloadWithQuantity(10);
        mockMvc.perform(post("/api/orders/checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Insufficient stock")));
    }

    @Test
    @DisplayName("Checkout with quantity above sane maximum limit (> 20) returns 400 Bad Request")
    public void testCheckoutWithQuantityAboveSaneLimitReturns400() throws Exception {
        Map<String, Object> payload = createCheckoutPayloadWithQuantity(25);
        mockMvc.perform(post("/api/orders/checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("cannot exceed maximum limit of 20 units")));
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // ADDITIONAL VALIDATION: Request Validation (Email, Postcode, Card rejection)
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Checkout with invalid UK postcode returns 400 Bad Request")
    public void testCheckoutWithInvalidPostcodeReturns400() throws Exception {
        Map<String, Object> payload = createCheckoutPayloadWithQuantity(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> shipping = (Map<String, Object>) payload.get("shippingInfo");
        Map<String, Object> invalidShipping = new HashMap<>(shipping);
        invalidShipping.put("shippingPostcode", "INVALID_123");
        payload.put("shippingInfo", invalidShipping);

        mockMvc.perform(post("/api/orders/checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Invalid UK postcode")));
    }

    @Test
    @DisplayName("Checkout with Credit/Debit Card returns 400 disabled message")
    public void testCheckoutWithCreditCardReturnsDisabledError() throws Exception {
        Map<String, Object> payload = createCheckoutPayloadWithQuantity(1);
        payload.put("paymentMethod", "Credit / Debit Card");

        mockMvc.perform(post("/api/orders/checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("temporarily disabled")));
    }

    private Map<String, Object> createCheckoutPayloadWithQuantity(Object quantity) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("shippingInfo", Map.of(
                "shippingName", "Sarah Connor",
                "shippingEmail", "sarah@example.co.uk",
                "shippingAddress", "42 Abbey Road",
                "shippingCity", "London",
                "shippingPostcode", "NW8 9AY"
        ));
        payload.put("items", List.of(
                Map.of(
                        "id", "pure-shea-butter",
                        "quantity", quantity
                )
        ));
        payload.put("paymentMethod", "PayPal");
        payload.put("paypalOrderId", "PP-MOCK-123");
        return payload;
    }
}

