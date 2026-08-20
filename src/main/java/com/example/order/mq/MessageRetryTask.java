package com.example.order.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.order.config.RabbitMQConfig;
import com.example.order.monitor.OrderMetrics;
import com.example.order.mq.entity.OrderMessageLog;
import com.example.order.mq.mapper.OrderMessageLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class MessageRetryTask {

    private final OrderMessageLogMapper messageLogMapper;
    private final RabbitTemplate rabbitTemplate;
    private final OrderMetrics orderMetrics;

    /**
     * Every 30 seconds, scan PENDING messages and retry sending.
     * Status is NOT updated to SENT here — MqConfirmCallback handles that after broker ack.
     */
    @Scheduled(fixedDelay = 30000)
    public void retryPendingMessages() {
        List<OrderMessageLog> pendingMessages = messageLogMapper.selectList(
                new LambdaQueryWrapper<OrderMessageLog>()
                        .in(OrderMessageLog::getStatus, "PENDING", "FAILED", "RETURNED")
                        .le(OrderMessageLog::getNextRetryTime, LocalDateTime.now())
                        .lt(OrderMessageLog::getRetryCount, 3)
        );

        if (pendingMessages.isEmpty()) {
            return;
        }

        log.info("Found {} pending messages to retry", pendingMessages.size());

        for (OrderMessageLog msg : pendingMessages) {
            try {
                String routingKey = getRoutingKey(msg.getMessageType());

                // Send with CorrelationData so MqConfirmCallback can update status to SENT on broker ack
                CorrelationData correlationData = new CorrelationData(msg.getMessageId());
                rabbitTemplate.convertAndSend(RabbitMQConfig.ORDER_EXCHANGE, routingKey, msg.getPayload(),
                        message -> {
                            message.getMessageProperties().setHeader("messageId", msg.getMessageId());
                            return message;
                        }, correlationData);

                // Only update retry bookkeeping — never touch status here.
                // status is owned by MqConfirmCallback (→ SENT) and ReturnsCallback (→ RETURNED).
                // Using a targeted update avoids a race where updateById(msg) could overwrite a
                // concurrent ConfirmCallback that already set status = SENT.
                int newRetryCount = msg.getRetryCount() + 1;
                LocalDateTime nextRetry = LocalDateTime.now().plusSeconds(30L * (newRetryCount + 1));
                messageLogMapper.update(null, new LambdaUpdateWrapper<OrderMessageLog>()
                        .eq(OrderMessageLog::getMessageId, msg.getMessageId())
                        .set(OrderMessageLog::getRetryCount, newRetryCount)
                        .set(OrderMessageLog::getNextRetryTime, nextRetry));
                orderMetrics.getMessageRetryCounter().increment();
                log.info("Retry sent, awaiting broker confirm: messageId={}, type={}, retryCount={}",
                        msg.getMessageId(), msg.getMessageType(), newRetryCount);

            } catch (Exception e) {
                int newRetryCount = msg.getRetryCount() + 1;
                LocalDateTime nextRetry = LocalDateTime.now().plusSeconds(30L * newRetryCount);
                LambdaUpdateWrapper<OrderMessageLog> errUpdate =
                        new LambdaUpdateWrapper<OrderMessageLog>()
                                .eq(OrderMessageLog::getMessageId, msg.getMessageId())
                                .set(OrderMessageLog::getRetryCount, newRetryCount)
                                .set(OrderMessageLog::getNextRetryTime, nextRetry);
                if (newRetryCount >= msg.getMaxRetry()) {
                    errUpdate.set(OrderMessageLog::getStatus, "FAILED");
                    log.error("Message exhausted max retries: messageId={}, type={}", msg.getMessageId(), msg.getMessageType());
                }
                messageLogMapper.update(null, errUpdate);
            }
        }
    }

    private String getRoutingKey(String messageType) {
        return switch (messageType) {
            case "ORDER_CREATED" -> RabbitMQConfig.RK_ORDER_CREATED;
            case "PAYMENT_CALLBACK" -> RabbitMQConfig.RK_ORDER_PAYMENT;
            case "ORDER_TIMEOUT" -> "order.delay";
            default -> throw new IllegalArgumentException("Unknown message type: " + messageType);
        };
    }
}
