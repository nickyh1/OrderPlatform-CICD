package com.example.order.mq;

import com.example.order.config.RabbitMQConfig;
import com.example.order.mq.entity.OrderMessageLog;
import com.example.order.mq.mapper.OrderMessageLogMapper;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderMessageProducer {

    private final RabbitTemplate rabbitTemplate;
    private final OrderMessageLogMapper messageLogMapper;
    private final ObjectMapper objectMapper;

    /**
     * Save message to local outbox table (called INSIDE the order transaction).
     * Returns the message log so caller can send it after transaction commits.
     */
    public OrderMessageLog saveMessageLog(Long orderId, String messageType, Map<String, Object> payload) {
        try {
            OrderMessageLog msgLog = new OrderMessageLog();
            msgLog.setMessageId(UUID.randomUUID().toString().replace("-", ""));
            msgLog.setOrderId(orderId);
            msgLog.setMessageType(messageType);
            msgLog.setPayload(objectMapper.writeValueAsString(payload));
            msgLog.setStatus("PENDING");
            msgLog.setRetryCount(0);
            msgLog.setMaxRetry(3);
            // Give broker confirm ack 30s to arrive before retry task picks this up
            msgLog.setNextRetryTime(LocalDateTime.now().plusSeconds(30));
            messageLogMapper.insert(msgLog);
            log.info("Message log saved: messageId={}, type={}, orderId={}", msgLog.getMessageId(), messageType, orderId);
            return msgLog;
        } catch (Exception e) {
            log.error("Failed to save message log: orderId={}, type={}", orderId, messageType, e);
            throw new RuntimeException("Failed to save message log", e);
        }
    }

    /**
     * Send message to RabbitMQ. Status stays PENDING until broker ack via MqConfirmCallback.
     * Called AFTER transaction commits.
     */
    public void sendMessage(OrderMessageLog msgLog, String routingKey) {
        try {
            CorrelationData correlationData = new CorrelationData(msgLog.getMessageId());
            rabbitTemplate.convertAndSend(RabbitMQConfig.ORDER_EXCHANGE, routingKey, msgLog.getPayload(),
                    message -> {
                        message.getMessageProperties().setHeader("messageId", msgLog.getMessageId());
                        return message;
                    }, correlationData);
            log.info("Message sent, awaiting broker confirm: messageId={}, routingKey={}", msgLog.getMessageId(), routingKey);
        } catch (Exception e) {
            log.error("Failed to send message: messageId={}, will be retried", msgLog.getMessageId(), e);
        }
    }

    /**
     * Send order to delay queue (for timeout detection). Status stays PENDING until broker ack.
     * Called AFTER transaction commits.
     */
    public void sendDelayMessage(OrderMessageLog msgLog) {
        try {
            CorrelationData correlationData = new CorrelationData(msgLog.getMessageId());
            rabbitTemplate.convertAndSend(RabbitMQConfig.ORDER_EXCHANGE, "order.delay", msgLog.getPayload(),
                    message -> {
                        message.getMessageProperties().setHeader("messageId", msgLog.getMessageId());
                        return message;
                    }, correlationData);
            log.info("Delay message sent, awaiting broker confirm: messageId={}, orderId={}", msgLog.getMessageId(), msgLog.getOrderId());
        } catch (Exception e) {
            log.error("Failed to send delay message: messageId={}, will be retried", msgLog.getMessageId(), e);
        }
    }
}