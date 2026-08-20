package com.example.order.order.entity;

import lombok.Getter;
import lombok.AllArgsConstructor;

@Getter
@AllArgsConstructor
public enum OrderStatus {

    PENDING("PENDING"),
    PAID("PAID"),
    CANCELLED("CANCELLED"),
    TIMEOUT("TIMEOUT");

    private final String value;

    /**
     * Check if transition from current status to target status is allowed.
     * PENDING -> PAID, CANCELLED, TIMEOUT
     * PAID / CANCELLED / TIMEOUT are terminal states.
     */
    public boolean canTransitTo(OrderStatus target) {
        if (this != PENDING) {
            return false; // terminal states cannot change
        }
        return target == PAID || target == CANCELLED || target == TIMEOUT;
    }

    public static OrderStatus fromValue(String value) {
        for (OrderStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown order status: " + value);
    }
}