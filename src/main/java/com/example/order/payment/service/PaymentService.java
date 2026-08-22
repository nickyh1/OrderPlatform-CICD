package com.example.order.payment.service;

import com.example.order.common.BusinessException;
import com.example.order.common.ResultCode;
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
            // Only a non-expired PENDING order can become PAID.
            int rows = orderMapper.markPaidIfPending(orderNo);

            if (rows == 0) {
                OrderInfo currentOrder =
                        orderService.getByOrderNo(orderNo);

                // Still PENDING means expire_time <= NOW(), but timeout
                // processing has not completed yet.
                if (OrderStatus.PENDING.getValue()
                        .equals(currentOrder.getStatus())) {

                    log.warn(
                            "Payment rejected because order has expired: " +
                                    "orderNo={}, expireTime={}",
                            orderNo,
                            currentOrder.getExpireTime()
                    );

                    throw new BusinessException(
                            ResultCode.ORDER_STATUS_INVALID
                    );
                }

                // Repeated callbacks for terminal orders are idempotent.
                log.info(
                        "Order already processed, skip payment success callback: " +
                                "orderNo={}, status={}",
                        orderNo,
                        currentOrder.getStatus()
                );

                return currentOrder;
            }

            inventoryService.confirmStock(
                    order.getProductId(),
                    order.getQuantity()
            );

            log.info("Payment success: orderNo={}", orderNo);
            order.setStatus(OrderStatus.PAID.getValue());

        } else {
            // Payment failure can only cancel a PENDING order.
            int rows = orderMapper.updateStatusFromPending(
                    orderNo,
                    OrderStatus.CANCELLED.getValue()
            );

            if (rows == 0) {
                OrderInfo currentOrder =
                        orderService.getByOrderNo(orderNo);

                log.info(
                        "Order already processed, skip payment failure callback: " +
                                "orderNo={}, status={}",
                        orderNo,
                        currentOrder.getStatus()
                );

                return currentOrder;
            }

            inventoryService.rollbackStock(
                    order.getProductId(),
                    order.getQuantity()
            );

            log.info(
                    "Payment failed, order cancelled: orderNo={}",
                    orderNo
            );

            order.setStatus(OrderStatus.CANCELLED.getValue());
        }

        return order;
    }
}