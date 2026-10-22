package com.lichtluxe.backend.dto;

import com.lichtluxe.backend.entity.Order.OrderStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class OrderStatusUpdateRequest {
    @NotNull
    private OrderStatus status;

    private String carrier;
    private String trackingNumber;
    private String trackingUrl;
    private String notes;
}
