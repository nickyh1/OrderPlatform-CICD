package com.example.order.payment.service;

import com.example.order.inventory.service.InventoryService;
import com.example.order.order.entity.OrderInfo;
import com.example.order.order.entity.OrderStatus;
import com.example.order.order.mapper.OrderMapper;
import com.example.order.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final OrderService orderService;
    private final OrderMapper orderMapper;
    private final InventoryService inventoryService;

    /**
     * Handle payment callback.
     * Success: PENDING -> PAID, locked_stock -> sold (total_stock decreases)
     * Failure: PENDING -> CANCELLED, rollback stock
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderInfo handleCallback(String orderNo, boolean success) {
        OrderInfo order = orderService.getByOrderNo(orderNo);

        if (success) {
            // Conditional update: atomically mark PAID, prevents race with timeout/cancel
            int rows = orderMapper.markPaidIfPending(orderNo);
            if (rows == 0) {
                log.info("Order already processed, skip payment success callback: orderNo={}", orderNo);
                return orderService.getByOrderNo(orderNo);
            }
            inventoryService.confirmStock(order.getProductId(), order.getQuantity());
            log.info("Payment success: orderNo={}", orderNo);
            order.setStatus(OrderStatus.PAID.getValue());

        } else {
            // Conditional update: atomically mark CANCELLED
            int rows = orderMapper.updateStatusFromPending(orderNo, OrderStatus.CANCELLED.getValue());
            if (rows == 0) {
                log.info("Order already processed, skip payment failure callback: orderNo={}", orderNo);
                return orderService.getByOrderNo(orderNo);
            }
            inventoryService.rollbackStock(order.getProductId(), order.getQuantity());
            log.info("Payment failed, order cancelled: orderNo={}", orderNo);
            order.setStatus(OrderStatus.CANCELLED.getValue());
        }

        return order;
    }
}