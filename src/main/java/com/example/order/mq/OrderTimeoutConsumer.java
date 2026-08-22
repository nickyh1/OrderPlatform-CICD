package com.example.order.mq;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.order.config.RabbitMQConfig;
import com.example.order.inventory.service.InventoryService;
import com.example.order.mq.entity.OrderMessageLog;
import com.example.order.mq.mapper.OrderMessageLogMapper;
import com.example.order.order.entity.OrderStatus;
import com.example.order.order.entity.OrderInfo;
import com.example.order.order.mapper.OrderMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTimeoutConsumer {

    private final OrderMapper orderMapper;
    private final InventoryService inventoryService;
    private final OrderMessageLogMapper messageLogMapper;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = RabbitMQConfig.ORDER_TIMEOUT_QUEUE)
    @Transactional(rollbackFor = Exception.class)
    public void onOrderTimeout(Message message) {
        String payload = new String(message.getBody());
        String messageId = (String) message.getMessageProperties().getHeader("messageId");
        log.info("Received ORDER_TIMEOUT: messageId={}", messageId);

        try {
            // Atomically claim the message: SENT/PENDING → CONSUMING.
            // If rows == 0, another thread already claimed or consumed it — skip safely.
            if (messageId != null) {
                int claimed = messageLogMapper.update(null, new LambdaUpdateWrapper<OrderMessageLog>()
                        .eq(OrderMessageLog::getMessageId, messageId)
                        .in(OrderMessageLog::getStatus, "SENT", "PENDING")
                        .set(OrderMessageLog::getStatus, "CONSUMING"));
                if (claimed == 0) {
                    log.info("Timeout message already claimed or consumed, skipping: messageId={}", messageId);
                    return;
                }
            }

            // Parse payload
            JsonNode node = objectMapper.readTree(payload);

            // 兼容旧镜像发送的双重编码 JSON：
            // "{\"orderNo\":\"ORD...\"}" -> {"orderNo":"ORD..."}
            if (node != null && node.isTextual()) {
                log.warn(
                        "Legacy double-encoded ORDER_TIMEOUT payload detected: messageId={}",
                        messageId
                );
                node = objectMapper.readTree(node.asText());
            }

            if (node == null
                    || !node.isObject()
                    || !node.hasNonNull("orderNo")
                    || !node.hasNonNull("productId")
                    || !node.hasNonNull("quantity")) {
                throw new IllegalArgumentException(
                        "Invalid ORDER_TIMEOUT payload: required fields are missing"
                );
            }

            String orderNo = node.get("orderNo").asText();
            Long productId = node.get("productId").asLong();
            int quantity = node.get("quantity").asInt();

            if (orderNo.isBlank() || productId <= 0 || quantity <= 0) {
                throw new IllegalArgumentException(
                        "Invalid ORDER_TIMEOUT payload: field values are invalid"
                );
            }

            // Only an expired PENDING order can transition to TIMEOUT.
            int rows = orderMapper.markTimeoutIfExpired(orderNo);

            if (rows == 0) {
                OrderInfo currentOrder = orderMapper.selectOne(
                        new LambdaQueryWrapper<OrderInfo>()
                                .eq(OrderInfo::getOrderNo, orderNo)
                );

                if (currentOrder == null) {
                    throw new IllegalArgumentException(
                            "ORDER_TIMEOUT references a missing order"
                    );
                }

                // The order is still PENDING, so the timeout message arrived early.
                // Throwing here lets Spring Retry try the same message again later.
                if (OrderStatus.PENDING.getValue().equals(currentOrder.getStatus())) {
                    log.warn(
                            "ORDER_TIMEOUT arrived before expire_time, retry later: " +
                                    "orderNo={}, expireTime={}",
                            orderNo,
                            currentOrder.getExpireTime()
                    );
                    throw new IllegalStateException(
                            "ORDER_TIMEOUT arrived before expire_time"
                    );
                }

                // PAID/CANCELLED/TIMEOUT are valid terminal states.
                // The timeout event no longer has business value, so consume it normally.
                log.info(
                        "Order already processed, skip timeout: orderNo={}, status={}",
                        orderNo,
                        currentOrder.getStatus()
                );

                if (messageId != null) {
                    messageLogMapper.update(
                            null,
                            new LambdaUpdateWrapper<OrderMessageLog>()
                                    .eq(OrderMessageLog::getMessageId, messageId)
                                    .set(OrderMessageLog::getStatus, "CONSUMED")
                    );
                }

                return;
            }

            inventoryService.rollbackStock(productId, quantity);

            if (messageId != null) {
                messageLogMapper.update(null, new LambdaUpdateWrapper<OrderMessageLog>()
                        .eq(OrderMessageLog::getMessageId, messageId)
                        .set(OrderMessageLog::getStatus, "CONSUMED"));
            }
            log.info("Order timeout processed: orderNo={}, messageId={}", orderNo, messageId);

        } catch (Exception e) {
            /*
             * 不在这里更新 FAILED。
             *
             * 当前方法处于数据库事务中。重新抛出异常后，消息状态、
             * 订单状态以及库存数据库操作都会一起回滚。
             *
             * Spring Retry 耗尽后，由 OrderMessageRecoverer 在一个新的
             * 独立事务中把消息标记为 FAILED，并投递到死信队列。
             */
            log.error(
                    "Failed to process order timeout: messageId={}",
                    messageId,
                    e
            );

            throw new RuntimeException(
                    "Timeout processing failed",
                    e
            );
        }
    }
}