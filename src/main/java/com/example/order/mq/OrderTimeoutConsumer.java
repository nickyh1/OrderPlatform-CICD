package com.example.order.mq;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.order.config.RabbitMQConfig;
import com.example.order.inventory.service.InventoryService;
import com.example.order.mq.entity.OrderMessageLog;
import com.example.order.mq.mapper.OrderMessageLogMapper;
import com.example.order.order.entity.OrderStatus;
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
            String orderNo = node.get("orderNo").asText();
            Long productId = node.get("productId").asLong();
            int quantity = node.get("quantity").asInt();

            // Conditional update: atomically transition PENDING -> TIMEOUT, prevents race with payment/cancel
            int rows = orderMapper.updateStatusFromPending(orderNo, OrderStatus.TIMEOUT.getValue());
            if (rows == 0) {
                log.info("Order {} already processed, skip timeout", orderNo);
                if (messageId != null) {
                    messageLogMapper.update(null, new LambdaUpdateWrapper<OrderMessageLog>()
                            .eq(OrderMessageLog::getMessageId, messageId)
                            .set(OrderMessageLog::getStatus, "CONSUMED"));
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
            log.error("Failed to process order timeout: messageId={}", messageId, e);
            // Roll CONSUMING back to FAILED so the retry task can re-deliver
            if (messageId != null) {
                messageLogMapper.update(null, new LambdaUpdateWrapper<OrderMessageLog>()
                        .eq(OrderMessageLog::getMessageId, messageId)
                        .eq(OrderMessageLog::getStatus, "CONSUMING")
                        .set(OrderMessageLog::getStatus, "FAILED"));
            }
            throw new RuntimeException("Timeout processing failed", e);
        }
    }
}