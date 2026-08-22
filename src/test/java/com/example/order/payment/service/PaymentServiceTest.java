package com.example.order.payment.service;

import com.example.order.common.BusinessException;
import com.example.order.common.ResultCode;
import com.example.order.inventory.service.InventoryService;
import com.example.order.order.entity.OrderInfo;
import com.example.order.order.entity.OrderStatus;
import com.example.order.order.mapper.OrderMapper;
import com.example.order.order.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final String ORDER_NO = "ORD-TEST-001";
    private static final long PRODUCT_ID = 1L;
    private static final int QUANTITY = 2;

    @Mock
    private OrderService orderService;

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private InventoryService inventoryService;

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(
                orderService,
                orderMapper,
                inventoryService
        );
    }

    @Test
    void paymentSuccessConfirmsStock() {
        OrderInfo order = orderWithStatus(OrderStatus.PENDING);
        when(orderService.getByOrderNo(ORDER_NO))
                .thenReturn(order);
        when(orderMapper.markPaidIfPending(ORDER_NO))
                .thenReturn(1);

        OrderInfo result =
                paymentService.handleCallback(ORDER_NO, true);

        assertEquals(OrderStatus.PAID.getValue(), result.getStatus());

        verify(orderMapper).markPaidIfPending(ORDER_NO);
        verify(inventoryService).confirmStock(PRODUCT_ID, QUANTITY);
        verify(orderMapper, never()).updateStatusFromPending(
                ORDER_NO,
                OrderStatus.CANCELLED.getValue()
        );
        verify(inventoryService, never())
                .rollbackStock(PRODUCT_ID, QUANTITY);
    }

    @Test
    void expiredPendingPaymentIsRejected() {
        OrderInfo order = orderWithStatus(OrderStatus.PENDING);
        order.setExpireTime(LocalDateTime.now().minusSeconds(1));

        when(orderService.getByOrderNo(ORDER_NO))
                .thenReturn(order);
        when(orderMapper.markPaidIfPending(ORDER_NO))
                .thenReturn(0);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> paymentService.handleCallback(ORDER_NO, true)
        );

        assertEquals(
                ResultCode.ORDER_STATUS_INVALID.getCode(),
                exception.getCode()
        );

        verify(orderMapper).markPaidIfPending(ORDER_NO);
        verify(inventoryService, never())
                .confirmStock(PRODUCT_ID, QUANTITY);
        verify(inventoryService, never())
                .rollbackStock(PRODUCT_ID, QUANTITY);
    }

    @Test
    void paymentFailureCancelsOrderAndRestoresStock() {
        OrderInfo order = orderWithStatus(OrderStatus.PENDING);

        when(orderService.getByOrderNo(ORDER_NO))
                .thenReturn(order);
        when(orderMapper.updateStatusFromPending(
                ORDER_NO,
                OrderStatus.CANCELLED.getValue()
        )).thenReturn(1);

        OrderInfo result =
                paymentService.handleCallback(ORDER_NO, false);

        assertEquals(
                OrderStatus.CANCELLED.getValue(),
                result.getStatus()
        );

        verify(orderMapper, never()).markPaidIfPending(ORDER_NO);
        verify(orderMapper).updateStatusFromPending(
                ORDER_NO,
                OrderStatus.CANCELLED.getValue()
        );
        verify(inventoryService)
                .rollbackStock(PRODUCT_ID, QUANTITY);
        verify(inventoryService, never())
                .confirmStock(PRODUCT_ID, QUANTITY);
    }

    @Test
    void repeatedSuccessfulCallbackForPaidOrderIsIdempotent() {
        OrderInfo paidOrder = orderWithStatus(OrderStatus.PAID);

        when(orderService.getByOrderNo(ORDER_NO))
                .thenReturn(paidOrder);
        when(orderMapper.markPaidIfPending(ORDER_NO))
                .thenReturn(0);

        OrderInfo result =
                paymentService.handleCallback(ORDER_NO, true);

        assertSame(paidOrder, result);

        verify(orderMapper).markPaidIfPending(ORDER_NO);
        verifyNoInteractions(inventoryService);
    }

    private OrderInfo orderWithStatus(OrderStatus status) {
        OrderInfo order = new OrderInfo();
        order.setOrderNo(ORDER_NO);
        order.setProductId(PRODUCT_ID);
        order.setQuantity(QUANTITY);
        order.setStatus(status.getValue());
        order.setExpireTime(LocalDateTime.now().plusMinutes(5));
        return order;
    }
}