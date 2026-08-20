package com.example.order.common;

import lombok.Getter;
import lombok.AllArgsConstructor;

@Getter
@AllArgsConstructor
public enum ResultCode {

    SUCCESS(200, "success"),
    BAD_REQUEST(400, "Bad request"),
    NOT_FOUND(404, "Resource not found"),
    INTERNAL_ERROR(500, "Internal server error"),

    // Business errors
    PRODUCT_NOT_FOUND(1001, "Product not found"),
    PRODUCT_OFF_SHELF(1002, "Product is off shelf"),
    STOCK_NOT_ENOUGH(1003, "Stock not enough"),
    ORDER_NOT_FOUND(1004, "Order not found"),
    ORDER_STATUS_INVALID(1005, "Order status transition not allowed"),
    RATE_LIMITED(1007, "Too many requests, please try again later"),
    DUPLICATE_REQUEST(1006, "Duplicate request, please do not resubmit"),
    STOCK_ROLLBACK_FAILED(1008, "Stock rollback failed: locked stock insufficient"),
    STOCK_CONFIRM_FAILED(1009, "Stock confirm failed: locked stock insufficient");


    private final int code;
    private final String message;
}