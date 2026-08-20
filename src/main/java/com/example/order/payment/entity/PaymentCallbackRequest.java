package com.example.order.payment.entity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class PaymentCallbackRequest {

    @NotBlank(message = "orderNo is required")
    private String orderNo;

    /**
     * true = payment success, false = payment failed
     */
    @NotNull(message = "success flag is required")
    private Boolean success;
}