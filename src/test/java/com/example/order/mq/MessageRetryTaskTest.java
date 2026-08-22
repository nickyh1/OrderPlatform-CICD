package com.example.order.mq;

import com.example.order.config.RabbitMQConfig;
import com.example.order.monitor.OrderMetrics;
import com.example.order.mq.entity.OrderMessageLog;
import com.example.order.mq.mapper.OrderMessageLogMapper;
import io.micrometer.core.instrument.Counter;
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

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;

@ExtendWith(MockitoExtension.class)
class MessageRetryTaskTest {

    @BeforeAll
    static void initializeMyBatisPlusMetadata() {
        MybatisConfiguration configuration =
                new MybatisConfiguration();

        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(
                        configuration,
                        "MessageRetryTaskTest"
                );

        assistant.setCurrentNamespace(
                OrderMessageLogMapper.class.getName()
        );

        TableInfoHelper.initTableInfo(
                assistant,
                OrderMessageLog.class
        );
    }

    @Mock
    private OrderMessageLogMapper messageLogMapper;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private OrderMetrics orderMetrics;

    @Mock
    private Counter messageRetryCounter;

    private MessageRetryTask retryTask;

    @BeforeEach
    void setUp() {
        retryTask = new MessageRetryTask(
                messageLogMapper,
                rabbitTemplate,
                orderMetrics,
                new ObjectMapper()
        );
    }

    @Test
    void retryPublishesStoredJsonAsJsonObject() {
        OrderMessageLog messageLog = createMessageLog();

        when(messageLogMapper.selectList(any()))
                .thenReturn(List.of(messageLog));
        when(orderMetrics.getMessageRetryCounter())
                .thenReturn(messageRetryCounter);

        retryTask.retryPendingMessages();

        ArgumentCaptor<Object> payloadCaptor =
                ArgumentCaptor.forClass(Object.class);

        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMQConfig.ORDER_EXCHANGE),
                eq("order.delay"),
                payloadCaptor.capture(),
                any(MessagePostProcessor.class),
                any(CorrelationData.class)
        );

        JsonNode payload = assertInstanceOf(
                JsonNode.class,
                payloadCaptor.getValue()
        );

        assertTrue(payload.isObject());
        assertFalse(payload.isTextual());
        assertEquals(
                "ORD-RETRY-001",
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

        verify(messageRetryCounter).increment();
    }

    private OrderMessageLog createMessageLog() {
        OrderMessageLog messageLog = new OrderMessageLog();
        messageLog.setMessageId("message-retry-001");
        messageLog.setMessageType("ORDER_TIMEOUT");
        messageLog.setStatus("PENDING");
        messageLog.setRetryCount(0);
        messageLog.setMaxRetry(3);
        messageLog.setNextRetryTime(
                LocalDateTime.now().minusSeconds(1)
        );
        messageLog.setPayload(
                """
                {
                  "orderNo": "ORD-RETRY-001",
                  "productId": 1,
                  "quantity": 2
                }
                """
        );
        return messageLog;
    }
}