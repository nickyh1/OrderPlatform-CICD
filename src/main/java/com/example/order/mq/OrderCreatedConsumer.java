package com.example.order.mq;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.order.config.RabbitMQConfig;
import com.example.order.mq.entity.OrderMessageLog;
import com.example.order.mq.mapper.OrderMessageLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCreatedConsumer {

    private final OrderMessageLogMapper messageLogMapper;

    @RabbitListener(queues = RabbitMQConfig.ORDER_CREATED_QUEUE)
    public void onOrderCreated(Message message) {
        String payload = new String(message.getBody());
        String messageId = (String) message.getMessageProperties().getHeader("messageId");
        log.info("Received ORDER_CREATED: messageId={}", messageId);

        // Consumer idempotent: atomically claim the message by transitioning SENT/PENDING → CONSUMING.
        // If rows == 0 another thread already claimed or consumed it — skip safely.
        if (messageId != null) {
            int claimed = messageLogMapper.update(null, new LambdaUpdateWrapper<OrderMessageLog>()
                    .eq(OrderMessageLog::getMessageId, messageId)
                    .in(OrderMessageLog::getStatus, "SENT", "PENDING")
                    .set(OrderMessageLog::getStatus, "CONSUMING"));

            if (claimed == 0) {
                log.info("Message already claimed or consumed, skipping: messageId={}", messageId);
                return;
            }

            try {
                // Process business logic
                log.info("Processing ORDER_CREATED: {}", payload);

                // Mark as consumed
                messageLogMapper.update(null, new LambdaUpdateWrapper<OrderMessageLog>()
                        .eq(OrderMessageLog::getMessageId, messageId)
                        .set(OrderMessageLog::getStatus, "CONSUMED"));
            } catch (Exception e) {
                // Roll CONSUMING back to FAILED so the retry task can re-deliver
                messageLogMapper.update(null, new LambdaUpdateWrapper<OrderMessageLog>()
                        .eq(OrderMessageLog::getMessageId, messageId)
                        .eq(OrderMessageLog::getStatus, "CONSUMING")
                        .set(OrderMessageLog::getStatus, "FAILED"));
                log.error("Failed to process ORDER_CREATED, marked FAILED: messageId={}", messageId, e);
                throw e;
            }
        }
    }
}