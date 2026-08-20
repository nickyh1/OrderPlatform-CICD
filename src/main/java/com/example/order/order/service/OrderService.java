package com.example.order.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.order.common.BusinessException;
import com.example.order.common.IdempotentService;
import com.example.order.common.ResultCode;
import com.example.order.config.RabbitMQConfig;
import com.example.order.inventory.service.InventoryService;
import com.example.order.monitor.OrderMetrics;
import com.example.order.mq.OrderMessageProducer;
import com.example.order.mq.entity.OrderMessageLog;
import com.example.order.order.entity.CreateOrderRequest;
import com.example.order.order.entity.OrderInfo;
import com.example.order.order.entity.OrderStatus;
import com.example.order.order.mapper.OrderMapper;
import com.example.order.product.entity.Product;
import com.example.order.product.service.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.micrometer.core.instrument.Timer;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderMapper orderMapper;
    private final ProductService productService;
    private final InventoryService inventoryService;
    private final IdempotentService idempotentService;
    private final OrderMessageProducer messageProducer;
    private final OrderMetrics orderMetrics;

    /**
     * Create order: validate -> idempotent check -> deduct stock -> create order -> save message log
     * After transaction commits: send MQ messages
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderInfo createOrder(CreateOrderRequest request) {
        Timer.Sample sample = Timer.start();

        try {
            // 0. Idempotent check
            if (!idempotentService.validateAndConsume(request.getIdempotentToken())) {
                orderMetrics.getIdempotentRejectCounter().increment();
                throw new BusinessException(ResultCode.DUPLICATE_REQUEST);
            }

            // 1. Validate product
            Product product = productService.getAvailableProduct(request.getProductId());

            // 2. Deduct stock
            inventoryService.deductStock(request.getProductId(), request.getQuantity());

            // Register rollback hook: if transaction rolls back, compensate Redis
            final Long productId = request.getProductId();
            final int quantity = request.getQuantity();
            org.springframework.transaction.support.TransactionSynchronizationManager
                    .registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            if (status == STATUS_ROLLED_BACK) {
                                inventoryService.compensateRedis(productId, quantity);
                            }
                        }
                    });

            // 3. Build and save order
            OrderInfo order = new OrderInfo();
            order.setOrderNo(generateOrderNo());
            order.setUserId(request.getUserId());
            order.setProductId(request.getProductId());
            order.setQuantity(request.getQuantity());
            order.setTotalAmount(product.getPrice().multiply(BigDecimal.valueOf(request.getQuantity())));
            order.setStatus(OrderStatus.PENDING.getValue());
            order.setIdempotentKey(request.getIdempotentToken());
            order.setExpireTime(LocalDateTime.now().plusMinutes(15));
            orderMapper.insert(order);

            // 4. Save message logs (inside transaction)
            Map<String, Object> payload = Map.of(
                    "orderNo", order.getOrderNo(),
                    "orderId", order.getId(),
                    "userId", order.getUserId(),
                    "productId", order.getProductId(),
                    "quantity", order.getQuantity(),
                    "totalAmount", order.getTotalAmount()
            );

            OrderMessageLog createdMsg = messageProducer.saveMessageLog(
                    order.getId(), "ORDER_CREATED", payload);
            OrderMessageLog delayMsg = messageProducer.saveMessageLog(
                    order.getId(), "ORDER_TIMEOUT", payload);

            log.info("Order created: orderNo={}, userId={}, productId={}, quantity={}",
                    order.getOrderNo(), order.getUserId(), order.getProductId(), order.getQuantity());

            // 5. Send MQ after commit
            org.springframework.transaction.support.TransactionSynchronizationManager
                    .registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            messageProducer.sendMessage(createdMsg, RabbitMQConfig.RK_ORDER_CREATED);
                            messageProducer.sendDelayMessage(delayMsg);
                        }
                    });

            // 6. Record metrics
            orderMetrics.getOrderSuccessCounter().increment();
            return order;

        } catch (BusinessException e) {
            orderMetrics.getOrderFailCounter().increment();
            throw e;
        } finally {
            sample.stop(orderMetrics.getOrderCreateTimer());
        }
    }

    /**
     * Query user's orders with pagination
     */
    public Page<OrderInfo> listUserOrders(Long userId, int pageNum, int pageSize) {
        Page<OrderInfo> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<OrderInfo> wrapper = new LambdaQueryWrapper<OrderInfo>()
                .eq(OrderInfo::getUserId, userId)
                .orderByDesc(OrderInfo::getCreateTime);
        return orderMapper.selectPage(page, wrapper);
    }

    /**
     * Get order detail by orderNo
     */
    public OrderInfo getByOrderNo(String orderNo) {
        OrderInfo order = orderMapper.selectOne(
                new LambdaQueryWrapper<OrderInfo>()
                        .eq(OrderInfo::getOrderNo, orderNo));
        if (order == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        return order;
    }

    /**
     * Cancel order: only PENDING orders can be cancelled
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderInfo cancelOrder(String orderNo) {
        OrderInfo order = getByOrderNo(orderNo);

        // Conditional update: only succeeds if order is still PENDING (prevents race with payment/timeout)
        int rows = orderMapper.updateStatusFromPending(orderNo, OrderStatus.CANCELLED.getValue());
        if (rows == 0) {
            log.info("Order already processed, skip cancel: orderNo={}", orderNo);
            return getByOrderNo(orderNo);
        }

        inventoryService.rollbackStock(order.getProductId(), order.getQuantity());

        log.info("Order cancelled: orderNo={}", orderNo);
        order.setStatus(OrderStatus.CANCELLED.getValue());
        return order;
    }

    /**
     * Generate unique order number: yyyyMMddHHmmss + 8-char UUID
     */
    private String generateOrderNo() {
        String timestamp = LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String uuid = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        return "ORD" + timestamp + uuid;
    }
}