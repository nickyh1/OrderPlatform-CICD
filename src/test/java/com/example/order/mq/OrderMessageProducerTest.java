package com.example.order.mq;

import com.example.order.config.RabbitMQConfig;
import com.example.order.mq.entity.OrderMessageLog;
import com.example.order.mq.mapper.OrderMessageLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OrderMessageProducerTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private OrderMessageLogMapper messageLogMapper;

    private OrderMessageProducer producer;

    @BeforeEach
    void setUp() {
        producer = new OrderMessageProducer(
                rabbitTemplate,
                messageLogMapper,
                new ObjectMapper()
        );
    }

    @Test
    void regularMessageIsPublishedAsJsonObject() {
        OrderMessageLog messageLog =
                createMessageLog("ORDER_CREATED");

        producer.sendMessage(
                messageLog,
                RabbitMQConfig.RK_ORDER_CREATED
        );

        JsonNode payload =
                capturePublishedPayload(
                        RabbitMQConfig.RK_ORDER_CREATED
                );

        assertValidJsonObject(payload);
    }

    @Test
    void delayMessageIsPublishedAsJsonObject() {
        OrderMessageLog messageLog =
                createMessageLog("ORDER_TIMEOUT");

        producer.sendDelayMessage(messageLog);

        JsonNode payload =
                capturePublishedPayload("order.delay");

        assertValidJsonObject(payload);
    }

    private JsonNode capturePublishedPayload(String routingKey) {
        ArgumentCaptor<Object> payloadCaptor =
                ArgumentCaptor.forClass(Object.class);

        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMQConfig.ORDER_EXCHANGE),
                eq(routingKey),
                payloadCaptor.capture(),
                any(MessagePostProcessor.class),
                any(CorrelationData.class)
        );

        return assertInstanceOf(
                JsonNode.class,
                payloadCaptor.getValue()
        );
    }

    private void assertValidJsonObject(JsonNode payload) {
        assertTrue(payload.isObject());
        assertFalse(payload.isTextual());
        assertEquals(
                "ORD-TEST-001",
                payload.get("orderNo").asText()
        );
        assertEquals(
                1L,
                payload.get("productId").asLong()
        );
        assertEquals(
                2,
                payload.get("quantity").asInt()
        );
    }

    private OrderMessageLog createMessageLog(String messageType) {
        OrderMessageLog messageLog = new OrderMessageLog();
        messageLog.setMessageId("message-test-001");
        messageLog.setMessageType(messageType);
        messageLog.setPayload(
                """
                {
                  "orderNo": "ORD-TEST-001",
                  "productId": 1,
                  "quantity": 2
                }
                """
        );
        return messageLog;
    }
}