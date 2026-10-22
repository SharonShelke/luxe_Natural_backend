package com.lichtluxe.backend.service;

import com.lichtluxe.backend.entity.EmailLog;
import com.lichtluxe.backend.entity.Order;
import com.lichtluxe.backend.entity.OrderItem;
import com.lichtluxe.backend.entity.User;
import com.lichtluxe.backend.repository.EmailLogRepository;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;

/**
 * Production-ready email service for LICHT LUXE NATURALS.
 * Integrates with GoDaddy Professional Email powered by Titan SMTP (smtp.titan.email).
 * Delivers branded HTML templates, audit logging, and resilient non-blocking dispatch.
 */
@Slf4j
@Service
public class EmailService {

    @Autowired(required = false)
    private JavaMailSender mailSender;

    @Autowired
    private EmailLogRepository emailLogRepository;

    @Value("${app.mail.from:info@lichtluxenaturals.org}")
    private String fromEmail;

    @Value("${app.mail.sender-name:LICHT LUXE NATURALS}")
    private String senderName;

    @Value("${app.frontend-url:https://lichtluxenaturals.org}")
    private String frontendUrl;

    // ─── 1. Account Creation Welcome Email ─────────────────────────────
    @Async
    public CompletableFuture<Boolean> sendWelcomeEmail(User user) {
        String subject = "Welcome to LICHT LUXE NATURALS — Pure Luxury Awaits";
        String html = buildWelcomeEmailHtml(user);
        return sendEmailAsync(user.getEmail(), subject, html, "WELCOME");
    }

    // ─── 2. Forgot Password OTP Email ──────────────────────────────────
    @Async
    public CompletableFuture<Boolean> sendPasswordResetOtp(String email, String firstName, String otpCode, int expiryMinutes) {
        String subject = "Your LICHT LUXE NATURALS Password Reset Code: " + otpCode;
        String html = buildPasswordResetOtpHtml(firstName, otpCode, expiryMinutes);
        return sendEmailAsync(email, subject, html, "PASSWORD_RESET_OTP");
    }

    // ─── Password Changed Notice ───────────────────────────────────────
    @Async
    public CompletableFuture<Boolean> sendPasswordChangedEmail(User user) {
        String subject = "Security Notification: Your LICHT LUXE NATURALS Password was Changed";
        String html = buildPasswordChangedHtml(user);
        return sendEmailAsync(user.getEmail(), subject, html, "PASSWORD_CHANGED");
    }

    // ─── 3. Order Confirmation Email ───────────────────────────────────
    @Async
    public CompletableFuture<Boolean> sendOrderConfirmationEmail(Order order) {
        String recipient = order.getUser() != null ? order.getUser().getEmail() : "customer@example.com";
        String subject = "Order Confirmed #" + order.getOrderNumber() + " — LICHT LUXE NATURALS";
        String html = buildOrderConfirmationHtml(order);
        return sendEmailAsync(recipient, subject, html, "ORDER_CONFIRMATION");
    }

    // ─── 4. Order Processing and Delivery Updates ───────────────────────
    @Async
    public CompletableFuture<Boolean> sendOrderStatusEmail(Order order, Order.OrderStatus status) {
        String recipient = order.getUser() != null ? order.getUser().getEmail() : "customer@example.com";
        String statusLabel = formatStatusLabel(status);
        String subject = "Order Update: " + statusLabel + " (#" + order.getOrderNumber() + ") — LICHT LUXE NATURALS";
        String html = buildOrderStatusHtml(order, status);
        return sendEmailAsync(recipient, subject, html, "ORDER_STATUS_" + status.name());
    }

    // ─── Core Dispatch with Safe Logging & Retry ───────────────────────
    public CompletableFuture<Boolean> sendEmailAsync(String to, String subject, String htmlContent, String emailType) {
        EmailLog emailLog = EmailLog.builder()
                .recipient(to)
                .subject(subject)
                .emailType(emailType)
                .status(EmailLog.Status.SENT)
                .createdAt(LocalDateTime.now())
                .build();

        int maxRetries = 2;
        int attempt = 0;
        boolean success = false;
        String lastError = null;

        while (attempt <= maxRetries && !success) {
            try {
                if (mailSender == null) {
                    log.warn("JavaMailSender is null. Simulating email to {}", to);
                    emailLog.setStatus(EmailLog.Status.SIMULATED);
                    emailLog.setErrorMessage("MailSender not configured; simulated email logged.");
                    emailLogRepository.save(emailLog);
                    return CompletableFuture.completedFuture(true);
                }

                MimeMessage message = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

                helper.setFrom(fromEmail, senderName);
                helper.setTo(to);
                helper.setSubject(subject);
                helper.setText(htmlContent, true);

                mailSender.send(message);
                success = true;
                emailLog.setStatus(attempt > 0 ? EmailLog.Status.RETRIED : EmailLog.Status.SENT);
                emailLog.setSentAt(LocalDateTime.now());
                emailLog.setRetryCount(attempt);
                log.info("Email [{}] sent successfully to {}", emailType, to);

            } catch (Exception ex) {
                attempt++;
                lastError = ex.getMessage();
                log.warn("Attempt {}/{} failed sending email [{}] to {}: {}", attempt, maxRetries + 1, emailType, to, lastError);
                if (attempt <= maxRetries) {
                    try {
                        Thread.sleep(1500L * attempt);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        if (!success) {
            emailLog.setStatus(EmailLog.Status.FAILED);
            emailLog.setErrorMessage(lastError);
            emailLog.setRetryCount(attempt - 1);
            log.error("Failed to deliver email [{}] to {} after {} attempts: {}", emailType, to, attempt, lastError);
        }

        try {
            emailLogRepository.save(emailLog);
        } catch (Exception dbEx) {
            log.error("Could not record email log: {}", dbEx.getMessage());
        }

        return CompletableFuture.completedFuture(success);
    }

    // ─── Branded HTML Templates ────────────────────────────────────────

    private String getEmailHeader() {
        return "<!DOCTYPE html>" +
                "<html lang='en'>" +
                "<head>" +
                "<meta charset='UTF-8'>" +
                "<meta name='viewport' content='width=device-width, initial-scale=1.0'>" +
                "<title>LICHT LUXE NATURALS</title>" +
                "<style>" +
                "  body { margin: 0; padding: 0; background-color: #FAF7F2; font-family: 'Helvetica Neue', Helvetica, Arial, sans-serif; color: #2D3A29; -webkit-font-smoothing: antialiased; }" +
                "  .container { max-width: 620px; margin: 30px auto; background-color: #FFFFFF; border: 1px solid #E5DFC5; border-radius: 6px; overflow: hidden; box-shadow: 0 10px 30px rgba(45,58,41,0.06); }" +
                "  .brand-bar { background-color: #2D3A29; color: #FAF7F2; padding: 24px; text-align: center; border-bottom: 3px solid #C5A059; }" +
                "  .brand-title { font-size: 26px; font-weight: 700; letter-spacing: 0.18em; margin: 0; color: #FAF7F2; font-family: 'Georgia', serif; }" +
                "  .brand-sub { font-size: 11px; letter-spacing: 0.28em; text-transform: uppercase; color: #C5A059; margin-top: 4px; font-weight: 600; }" +
                "  .loveworld-tag { display: inline-block; background-color: rgba(197,160,89,0.18); color: #C5A059; border: 1px solid #C5A059; padding: 3px 10px; border-radius: 20px; font-size: 10px; letter-spacing: 0.15em; font-weight: 700; margin-top: 10px; }" +
                "  .content { padding: 36px 32px; }" +
                "  h1, h2 { color: #2D3A29; font-family: 'Georgia', serif; font-weight: 500; margin-top: 0; }" +
                "  p { font-size: 15px; line-height: 1.65; color: #5C635B; margin: 14px 0; }" +
                "  .otp-box { background-color: #FDFCF9; border: 2px dashed #C5A059; border-radius: 8px; padding: 22px; text-align: center; margin: 28px 0; }" +
                "  .otp-code { font-size: 38px; font-weight: 700; letter-spacing: 0.25em; color: #2D3A29; font-family: monospace; }" +
                "  .btn { display: inline-block; background-color: #2D3A29; color: #FAF7F2 !important; text-decoration: none; padding: 14px 32px; border-radius: 4px; font-size: 13px; font-weight: 700; letter-spacing: 0.12em; text-transform: uppercase; margin: 18px 0; }" +
                "  .receipt-table { width: 100%; border-collapse: collapse; margin: 24px 0; font-size: 14px; }" +
                "  .receipt-table th { background-color: #F8F6F0; color: #6E5535; text-transform: uppercase; font-size: 11px; letter-spacing: 0.1em; padding: 12px 10px; text-align: left; border-bottom: 2px solid #E5DFC5; }" +
                "  .receipt-table td { padding: 14px 10px; border-bottom: 1px solid #ECE7D8; color: #2D3A29; }" +
                "  .receipt-table tr:last-child td { border-bottom: none; }" +
                "  .totals-row td { font-weight: 700; font-size: 16px; color: #2D3A29; border-top: 2px solid #2D3A29; padding-top: 14px; }" +
                "  .card-box { background-color: #FAF8F3; border: 1px solid #E8E2D2; border-radius: 6px; padding: 18px 20px; margin: 20px 0; }" +
                "  .footer { background-color: #F6F3EB; padding: 28px; text-align: center; border-top: 1px solid #E5DFC5; font-size: 12px; color: #7A8279; line-height: 1.6; }" +
                "  .footer a { color: #6E5535; text-decoration: none; font-weight: 600; }" +
                "</style>" +
                "</head>" +
                "<body>" +
                "<div class='container'>" +
                "  <div class='brand-bar'>" +
                "    <div class='brand-title'>LICHT</div>" +
                "    <div class='brand-sub'>LUXE NATURALS</div>" +
                "    <div class='loveworld-tag'>MADE IN LOVEWORLD</div>" +
                "  </div>" +
                "  <div class='content'>";
    }

    private String getEmailFooter() {
        return "  </div>" +
                "  <div class='footer'>" +
                "    <p style='margin: 0 0 8px 0; font-weight: 600; color: #2D3A29; font-size: 13px;'>LICHT LUXE NATURALS</p>" +
                "    <p style='margin: 0 0 10px 0;'>Pure. Natural. Made with Love and from LOVEWORLD.</p>" +
                "    <p style='margin: 0 0 12px 0;'>Sender: <a href='mailto:" + fromEmail + "'>" + fromEmail + "</a> | Phone: 07831082965</p>" +
                "    <p style='margin: 0; font-size: 11px; color: #9A9E96;'>&copy; " + LocalDateTime.now().getYear() + " LICHT Luxe Naturals. All rights reserved.</p>" +
                "  </div>" +
                "</div>" +
                "</body>" +
                "</html>";
    }

    private String buildWelcomeEmailHtml(User user) {
        String name = user.getFirstName() != null ? user.getFirstName() : "Valued Customer";
        return getEmailHeader() +
                "<h1 style='font-size: 24px; color: #2D3A29; margin-bottom: 8px;'>Welcome to the Family, " + name + "!</h1>" +
                "<p style='color: #C5A059; font-size: 13px; font-weight: 700; letter-spacing: 0.1em; text-transform: uppercase;'>Your Member Account Has Been Created</p>" +
                "<p>We are delighted to welcome you to <strong>LICHT LUXE NATURALS</strong>. Your account is now active and ready for you to explore our handcrafted, 100% natural and unrefined formulations made with unconditional love.</p>" +
                "<div class='card-box'>" +
                "  <h3 style='margin-top: 0; color: #2D3A29; font-size: 15px;'>With your new account, you can:</h3>" +
                "  <ul style='margin: 0; padding-left: 20px; color: #5C635B; font-size: 14px; line-height: 1.8;'>" +
                "    <li>Track current orders and view your purchase history</li>" +
                "    <li>Save your delivery preferences for faster, seamless checkout</li>" +
                "    <li>Curate your personal wishlist of pure natural beauty essentials</li>" +
                "    <li>Enjoy early access to seasonal batches and exclusive member offers</li>" +
                "  </ul>" +
                "</div>" +
                "<div style='text-align: center; margin: 30px 0;'>" +
                "  <a href='" + frontendUrl + "' class='btn'>Explore the Collection</a>" +
                "</div>" +
                "<p style='font-size: 13px; color: #7A8279;'>If you have any questions or require bespoke skincare recommendations, our team is always here for you at <a href='mailto:" + fromEmail + "' style='color: #6E5535;'>" + fromEmail + "</a>.</p>" +
                getEmailFooter();
    }

    private String buildPasswordResetOtpHtml(String firstName, String otpCode, int expiryMinutes) {
        String greeting = (firstName != null && !firstName.isBlank()) ? "Dear " + firstName + "," : "Hello,";
        return getEmailHeader() +
                "<h1 style='font-size: 22px; color: #2D3A29;'>Password Reset Request</h1>" +
                "<p>" + greeting + "</p>" +
                "<p>We received a request to reset the password for your LICHT LUXE NATURALS account. Use the one-time passcode (OTP) below to securely verify your identity and set a new password:</p>" +
                "<div class='otp-box'>" +
                "  <div style='font-size: 11px; letter-spacing: 0.2em; text-transform: uppercase; color: #6E5535; margin-bottom: 8px; font-weight: 700;'>One-Time Verification Passcode</div>" +
                "  <div class='otp-code'>" + otpCode + "</div>" +
                "  <div style='font-size: 12px; color: #8A7352; margin-top: 8px;'>Valid for " + expiryMinutes + " minutes • Maximum 5 attempts</div>" +
                "</div>" +
                "<div class='card-box' style='border-left: 4px solid #C5A059;'>" +
                "  <strong style='color: #2D3A29; font-size: 13px;'>Security Notice:</strong>" +
                "  <p style='margin: 6px 0 0 0; font-size: 13px;'>If you did not initiate this password reset request, please rest assured that your account remains secure. No changes have been made. You can safely disregard this email or notify us immediately at <a href='mailto:" + fromEmail + "' style='color: #6E5535;'>" + fromEmail + "</a>.</p>" +
                "</div>" +
                getEmailFooter();
    }

    private String buildPasswordChangedHtml(User user) {
        String name = user.getFirstName() != null ? user.getFirstName() : "Customer";
        return getEmailHeader() +
                "<h1 style='font-size: 22px; color: #2D3A29;'>Password Successfully Updated</h1>" +
                "<p>Dear " + name + ",</p>" +
                "<p>This email confirms that the password for your LICHT LUXE NATURALS account was successfully updated on <strong>" +
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd MMMM yyyy, HH:mm")) + " UTC</strong>.</p>" +
                "<div class='card-box' style='border-left: 4px solid #2D3A29;'>" +
                "  <p style='margin: 0; font-size: 13px;'>You can now sign in with your new credentials. If you did NOT make this change, please contact our support team immediately at <a href='mailto:" + fromEmail + "' style='color: #6E5535; font-weight: 700;'>" + fromEmail + "</a> so we can protect your account.</p>" +
                "</div>" +
                "<div style='text-align: center; margin: 25px 0;'>" +
                "  <a href='" + frontendUrl + "' class='btn'>Go to LICHT LUXE NATURALS</a>" +
                "</div>" +
                getEmailFooter();
    }

    private String buildOrderConfirmationHtml(Order order) {
        StringBuilder itemsHtml = new StringBuilder();
        BigDecimal subtotal = BigDecimal.ZERO;

        for (OrderItem item : order.getItems()) {
            BigDecimal lineTotal = item.getLineTotal() != null
                    ? item.getLineTotal()
                    : item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
            subtotal = subtotal.add(lineTotal);

            itemsHtml.append("<tr>")
                    .append("<td><strong>").append(item.getProductName()).append("</strong></td>")
                    .append("<td style='text-align: center;'>").append(item.getQuantity()).append("</td>")
                    .append("<td style='text-align: right;'>£").append(String.format("%.2f", item.getUnitPrice())).append("</td>")
                    .append("<td style='text-align: right; font-weight: 600;'>£").append(String.format("%.2f", lineTotal)).append("</td>")
                    .append("</tr>");
        }

        String recipientName = order.getShippingName() != null ? order.getShippingName() :
                (order.getUser() != null ? order.getUser().getFirstName() + " " + order.getUser().getLastName() : "Valued Customer");

        String formattedDate = order.getCreatedAt() != null
                ? order.getCreatedAt().format(DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm"))
                : LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm"));

        return getEmailHeader() +
                "<h1 style='font-size: 24px; color: #2D3A29; margin-bottom: 4px;'>Thank You for Your Order!</h1>" +
                "<p style='color: #C5A059; font-size: 13px; font-weight: 700; letter-spacing: 0.1em; text-transform: uppercase;'>Order Confirmed: #" + order.getOrderNumber() + "</p>" +
                "<p>Dear " + recipientName + ",</p>" +
                "<p>We have received your order and are preparing it with meticulous care. Below is your detailed order receipt:</p>" +
                
                "<div class='card-box' style='display: flex; justify-content: space-between;'>" +
                "  <div><strong>Order Number:</strong> " + order.getOrderNumber() + "<br><strong>Order Date:</strong> " + formattedDate + "</div>" +
                "  <div style='margin-top: 8px;'><strong>Payment Status:</strong> <span style='color: #2D3A29; font-weight: 700;'>" + order.getStatus().name() + "</span> (" + (order.getPaymentMethod() != null ? order.getPaymentMethod() : "Paid") + ")</div>" +
                "</div>" +

                "<table class='receipt-table'>" +
                "  <thead>" +
                "    <tr>" +
                "      <th>Product</th>" +
                "      <th style='text-align: center;'>Qty</th>" +
                "      <th style='text-align: right;'>Price</th>" +
                "      <th style='text-align: right;'>Total</th>" +
                "    </tr>" +
                "  </thead>" +
                "  <tbody>" +
                itemsHtml.toString() +
                "    <tr>" +
                "      <td colspan='3' style='text-align: right; padding-top: 14px; color: #6E5535;'>Subtotal:</td>" +
                "      <td style='text-align: right; padding-top: 14px;'>£" + String.format("%.2f", subtotal) + "</td>" +
                "    </tr>" +
                "    <tr>" +
                "      <td colspan='3' style='text-align: right; color: #6E5535;'>Standard UK Delivery:</td>" +
                "      <td style='text-align: right; color: #2D3A29; font-weight: 600;'>FREE</td>" +
                "    </tr>" +
                "    <tr class='totals-row'>" +
                "      <td colspan='3' style='text-align: right;'>Total Amount Paid:</td>" +
                "      <td style='text-align: right;'>£" + String.format("%.2f", order.getTotalAmount()) + "</td>" +
                "    </tr>" +
                "  </tbody>" +
                "</table>" +

                "<div class='card-box'>" +
                "  <h3 style='margin-top: 0; color: #2D3A29; font-size: 14px; text-transform: uppercase; letter-spacing: 0.08em;'>Delivery Address</h3>" +
                "  <p style='margin: 0; font-size: 14px; color: #2D3A29; line-height: 1.5;'>" +
                "    <strong>" + recipientName + "</strong><br>" +
                (order.getShippingAddress() != null ? order.getShippingAddress() + "<br>" : "") +
                (order.getShippingCity() != null ? order.getShippingCity() + ", " : "") +
                (order.getShippingPostcode() != null ? order.getShippingPostcode() : "") +
                (order.getShippingPhone() != null ? "<br>Phone: " + order.getShippingPhone() : "") +
                "  </p>" +
                "</div>" +

                "<div style='text-align: center; margin: 30px 0;'>" +
                "  <a href='" + frontendUrl + "' class='btn'>Visit LICHT LUXE NATURALS</a>" +
                "</div>" +
                getEmailFooter();
    }

    private String buildOrderStatusHtml(Order order, Order.OrderStatus status) {
        String recipientName = order.getShippingName() != null ? order.getShippingName() :
                (order.getUser() != null ? order.getUser().getFirstName() : "Valued Customer");

        String statusTitle;
        String statusDescription;
        String accentColor = "#2D3A29";

        switch (status) {
            case CONFIRMED:
                statusTitle = "Your Order is Confirmed";
                statusDescription = "We have received your order and confirmed the details. Our team is now scheduling fulfillment.";
                accentColor = "#C5A059";
                break;
            case PROCESSING:
                statusTitle = "Your Order is Being Prepared";
                statusDescription = "Your pure natural formulations are currently being carefully hand-packaged and inspected to ensure the highest standard of purity and quality.";
                accentColor = "#6E5535";
                break;
            case SHIPPED:
                statusTitle = "Your Order Has Been Dispatched!";
                statusDescription = "Your package has left our workshop and is on its way to you. Tracking details are provided below.";
                accentColor = "#2D3A29";
                break;
            case OUT_FOR_DELIVERY:
                statusTitle = "Out for Delivery Today";
                statusDescription = "Your order is with the courier and out for delivery to your address today. Please ensure someone is available to receive it.";
                accentColor = "#2D3A29";
                break;
            case DELIVERED:
                statusTitle = "Your Order Has Been Delivered";
                statusDescription = "Your package has been successfully delivered! We hope you love your pure, natural skincare essentials. Made with love and from LOVEWORLD.";
                accentColor = "#2D3A29";
                break;
            case CANCELLED:
                statusTitle = "Order Cancelled";
                statusDescription = "Your order #" + order.getOrderNumber() + " has been cancelled. If you did not request this or have questions, please reach out to us.";
                accentColor = "#8B0000";
                break;
            case REFUNDED:
                statusTitle = "Order Refund Processed";
                statusDescription = "A refund has been processed for order #" + order.getOrderNumber() + " in the amount of £" + String.format("%.2f", order.getTotalAmount()) + ". It should appear on your original payment method in 3–5 business days.";
                accentColor = "#6E5535";
                break;
            default:
                statusTitle = "Order Status Update: " + status.name();
                statusDescription = "Your order status has changed to " + status.name() + ".";
                break;
        }

        StringBuilder trackingBox = new StringBuilder();
        if (order.getTrackingNumber() != null && !order.getTrackingNumber().isBlank()) {
            trackingBox.append("<div class='card-box' style='border-left: 4px solid ").append(accentColor).append(";'>")
                    .append("<h3 style='margin-top: 0; color: #2D3A29; font-size: 14px; text-transform: uppercase; letter-spacing: 0.08em;'>Tracking Information</h3>")
                    .append("<p style='margin: 4px 0; font-size: 14px;'><strong>Carrier:</strong> ").append(order.getCarrier() != null ? order.getCarrier() : "Royal Mail").append("</p>")
                    .append("<p style='margin: 4px 0; font-size: 14px;'><strong>Tracking Number:</strong> <span style='font-family: monospace; font-weight: 700;'>").append(order.getTrackingNumber()).append("</span></p>");

            if (order.getTrackingUrl() != null && !order.getTrackingUrl().isBlank()) {
                trackingBox.append("<div style='margin-top: 12px;'><a href='").append(order.getTrackingUrl()).append("' class='btn' style='padding: 10px 22px; font-size: 12px;'>Track Package Online</a></div>");
            }
            trackingBox.append("</div>");
        }

        return getEmailHeader() +
                "<h1 style='font-size: 24px; color: " + accentColor + "; margin-bottom: 4px;'>" + statusTitle + "</h1>" +
                "<p style='color: #C5A059; font-size: 13px; font-weight: 700; letter-spacing: 0.1em; text-transform: uppercase;'>Order #" + order.getOrderNumber() + "</p>" +
                "<p>Dear " + recipientName + ",</p>" +
                "<p>" + statusDescription + "</p>" +
                trackingBox.toString() +
                "<div class='card-box'>" +
                "  <div style='display: flex; justify-content: space-between; font-size: 14px;'>" +
                "    <div><strong>Order Total:</strong> £" + String.format("%.2f", order.getTotalAmount()) + "</div>" +
                "    <div><strong>Items:</strong> " + order.getItems().size() + " product(s)</div>" +
                "  </div>" +
                "</div>" +
                "<div style='text-align: center; margin: 30px 0;'>" +
                "  <a href='" + frontendUrl + "' class='btn'>View Order at LICHT LUXE NATURALS</a>" +
                "</div>" +
                getEmailFooter();
    }

    private String formatStatusLabel(Order.OrderStatus status) {
        switch (status) {
            case CONFIRMED: return "Confirmed";
            case PROCESSING: return "Processing";
            case SHIPPED: return "Dispatched & Shipped";
            case OUT_FOR_DELIVERY: return "Out for Delivery";
            case DELIVERED: return "Delivered";
            case CANCELLED: return "Cancelled";
            case REFUNDED: return "Refunded";
            case PAID: return "Payment Received";
            default: return status.name();
        }
    }
}
