package com.lichtluxe.backend.controller;

import com.lichtluxe.backend.dto.MessageResponse;
import com.lichtluxe.backend.entity.EmailLog;
import com.lichtluxe.backend.entity.Order;
import com.lichtluxe.backend.entity.OrderItem;
import com.lichtluxe.backend.entity.User;
import com.lichtluxe.backend.repository.EmailLogRepository;
import com.lichtluxe.backend.service.EmailService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Test & Verification Controller for LICHT LUXE NATURALS automated email workflows.
 * Allows safe verification of GoDaddy Titan SMTP connectivity and HTML template rendering.
 */
@CrossOrigin(origins = "*", maxAge = 3600)
@RestController
@RequestMapping("/api/test/email")
public class EmailTestController {

    @Autowired
    private EmailService emailService;

    @Autowired
    private EmailLogRepository emailLogRepository;

    @GetMapping("/logs")
    public List<EmailLog> getEmailLogs() {
        return emailLogRepository.findTop30ByOrderByCreatedAtDesc();
    }

    @PostMapping("/welcome")
    public ResponseEntity<?> testWelcomeEmail(@RequestParam(defaultValue = "info@lichtluxenaturals.org") String email) {
        User dummyUser = User.builder()
                .firstName("Eleanor")
                .lastName("Vance")
                .email(email)
                .build();
        emailService.sendWelcomeEmail(dummyUser);
        return ResponseEntity.ok(new MessageResponse("Welcome email triggered to " + email));
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<?> testForgotPasswordEmail(@RequestParam(defaultValue = "info@lichtluxenaturals.org") String email) {
        emailService.sendPasswordResetOtp(email, "Eleanor", "749215", 10);
        return ResponseEntity.ok(new MessageResponse("Password reset OTP email triggered to " + email));
    }

    @PostMapping("/order-confirmation")
    public ResponseEntity<?> testOrderConfirmationEmail(@RequestParam(defaultValue = "info@lichtluxenaturals.org") String email) {
        User user = User.builder().firstName("Eleanor").lastName("Vance").email(email).build();
        Order order = createMockOrder(user);
        emailService.sendOrderConfirmationEmail(order);
        return ResponseEntity.ok(new MessageResponse("Order confirmation email triggered for #" + order.getOrderNumber() + " to " + email));
    }

    @PostMapping("/order-status")
    public ResponseEntity<?> testOrderStatusEmail(
            @RequestParam(defaultValue = "info@lichtluxenaturals.org") String email,
            @RequestParam(defaultValue = "SHIPPED") Order.OrderStatus status,
            @RequestParam(defaultValue = "Royal Mail") String carrier,
            @RequestParam(defaultValue = "GB982341209LLN") String trackingNumber) {

        User user = User.builder().firstName("Eleanor").lastName("Vance").email(email).build();
        Order order = createMockOrder(user);
        order.setCarrier(carrier);
        order.setTrackingNumber(trackingNumber);
        order.setTrackingUrl("https://www.royalmail.com/track-your-item#/tracking-results/" + trackingNumber);

        emailService.sendOrderStatusEmail(order, status);
        return ResponseEntity.ok(new MessageResponse("Order status update email (" + status.name() + ") triggered to " + email));
    }

    private Order createMockOrder(User user) {
        Order order = Order.builder()
                .orderNumber("LLN-TEST-8492")
                .user(user)
                .status(Order.OrderStatus.CONFIRMED)
                .totalAmount(BigDecimal.valueOf(62.00))
                .shippingName("Eleanor Vance")
                .shippingAddress("14 Kensington High Street, Suite 4B")
                .shippingCity("London")
                .shippingPostcode("W8 4PT")
                .shippingPhone("07831082965")
                .paymentMethod("PAYPAL")
                .items(new ArrayList<>())
                .build();

        OrderItem item1 = OrderItem.builder()
                .productName("Pure Shea Butter (Raw & Unrefined)")
                .quantity(1)
                .unitPrice(BigDecimal.valueOf(24.00))
                .lineTotal(BigDecimal.valueOf(24.00))
                .build();
        OrderItem item2 = OrderItem.builder()
                .productName("Whipped Shea Body Butter (Warm Vanilla & Cocoa)")
                .quantity(1)
                .unitPrice(BigDecimal.valueOf(38.00))
                .lineTotal(BigDecimal.valueOf(38.00))
                .build();

        order.addItem(item1);
        order.addItem(item2);
        return order;
    }
}
