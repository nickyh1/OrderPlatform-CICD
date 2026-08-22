package com.example.order.mq;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.example.order.config.RabbitMQConfig;
import com.example.order.mq.entity.OrderMessageLog;
import com.example.order.mq.mapper.OrderMessageLogMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderMessageRecovererTest {

    private static final String MESSAGE_ID =
            "message-recoverer-001";

    @BeforeAll
    static void initializeMyBatisPlusMetadata() {
        MybatisConfiguration configuration =
                new MybatisConfiguration();

        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(
                        configuration,
                        "OrderMessageRecovererTest"
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

    private OrderMessageRecoverer recoverer;

    @BeforeEach
    void setUp() {
        recoverer = new OrderMessageRecoverer(
                messageLogMapper,
                rabbitTemplate
        );
    }

    @Test
    void exhaustedRetryMarksFailedAndRepublishesMessage() {
        when(messageLogMapper.update(
                isNull(),
                any(LambdaUpdateWrapper.class)
        )).thenReturn(1);

        Message message = message(true);

        RuntimeException cause =
                new RuntimeException(
                        "permanent timeout processing failure"
                );

        recoverer.recover(message, cause);

        ArgumentCaptor<LambdaUpdateWrapper<OrderMessageLog>>
                updateCaptor =
                ArgumentCaptor.forClass(
                        LambdaUpdateWrapper.class
                );

        verify(messageLogMapper).update(
                isNull(),
                updateCaptor.capture()
        );

        LambdaUpdateWrapper<OrderMessageLog> updateWrapper =
                updateCaptor.getValue();

        /*
         * MyBatis-Plus 会将查询条件和 SET 值放入参数表。
         * 这里验证：
         *
         * message_id = MESSAGE_ID
         * status != CONSUMED
         * status = FAILED
         * next_retry_time = LocalDateTime
         */
        /*
         * LambdaUpdateWrapper 延迟生成 SQL 及参数。
         * 先渲染 WHERE 和 SET，参数才会进入参数表。
         */
        String whereSql =
                updateWrapper.getSqlSegment();

        String setSql =
                updateWrapper.getSqlSet();

        assertTrue(
                whereSql.contains("message_id")
        );

        assertTrue(
                whereSql.contains("status")
        );

        assertTrue(
                setSql.contains("status")
        );

        assertTrue(
                setSql.contains("next_retry_time")
        );

        Map<String, Object> parameters =
                updateWrapper.getParamNameValuePairs();

        assertTrue(
                parameters.containsValue(MESSAGE_ID)
        );

        assertTrue(
                parameters.containsValue("CONSUMED")
        );

        assertTrue(
                parameters.containsValue("FAILED")
        );

        assertTrue(
                parameters.values()
                        .stream()
                        .anyMatch(LocalDateTime.class::isInstance)
        );

        verify(rabbitTemplate).send(
                eq(RabbitMQConfig.ORDER_DLX_EXCHANGE),
                eq(RabbitMQConfig.RK_ORDER_DEAD),
                same(message)
        );

        /*
         * RepublishMessageRecoverer 会给消息增加异常信息，
         * 供 DeadLetterConsumer 和人工排查使用。
         */
        assertNotNull(
                message.getMessageProperties().getHeader(
                        RepublishMessageRecoverer
                                .X_EXCEPTION_MESSAGE
                )
        );
    }

    @Test
    void messageWithoutIdIsStillRepublished() {
        Message message = message(false);

        RuntimeException cause =
                new RuntimeException(
                        "message without id"
                );

        recoverer.recover(message, cause);

        /*
         * 没有 messageId 时无法定位数据库消息记录，
         * 因此不能盲目更新。
         */
        verifyNoInteractions(messageLogMapper);

        /*
         * 但原消息仍然必须进入死信队列，以免完全丢失。
         */
        verify(rabbitTemplate).send(
                eq(RabbitMQConfig.ORDER_DLX_EXCHANGE),
                eq(RabbitMQConfig.RK_ORDER_DEAD),
                same(message)
        );
    }

    private Message message(boolean includeMessageId) {
        MessageProperties properties =
                new MessageProperties();

        properties.setReceivedExchange(
                RabbitMQConfig.ORDER_DLX_EXCHANGE
        );

        properties.setReceivedRoutingKey(
                RabbitMQConfig.RK_ORDER_TIMEOUT
        );

        if (includeMessageId) {
            properties.setHeader(
                    "messageId",
                    MESSAGE_ID
            );
        }

        return new Message(
                """
                {
                  "orderNo": "ORD-RECOVERER-001",
                  "productId": 1,
                  "quantity": 2
                }
                """.getBytes(StandardCharsets.UTF_8),
                properties
        );
    }
}