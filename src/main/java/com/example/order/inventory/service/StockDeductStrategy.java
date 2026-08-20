package com.example.order.inventory.service;

public interface StockDeductStrategy {

    /**
     * Deduct stock. Throws BusinessException if stock not enough.
     */
    void deduct(Long productId, int quantity);

    /**
     * Rollback stock on cancel/timeout.
     */
    void rollback(Long productId, int quantity);

    /**
     * Confirm stock on payment success.
     */
    void confirm(Long productId, int quantity);

    String name();
}