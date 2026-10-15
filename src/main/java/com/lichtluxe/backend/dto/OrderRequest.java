package com.lichtluxe.backend.dto;

import lombok.Data;

@Data
public class OrderRequest {
    private String shippingName;
    private String shippingAddress;
    private String shippingCity;
    private String shippingPostcode;
    private String shippingPhone;
    private String orderNotes;
}
