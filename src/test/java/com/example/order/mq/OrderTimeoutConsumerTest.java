package com.example.order.mq;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.example.order.inventory.service.InventoryService;
import com.example.order.mq.entity.OrderMessageLog;
import com.example.order.mq.mapper.OrderMessageLogMapper;
import com.example.order.order.entity.OrderInfo;
import com.example.order.order.entity.OrderStatus;
import com.example.order.order.mapper.OrderMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderTimeoutConsumerTest {

    private static final String ORDER_NO = "ORD-TIMEOUT-001";
    private static final String MESSAGE_ID = "message-timeout-001";
    private static final long PRODUCT_ID = 1L;
    private static final int QUANTITY = 2;

    @BeforeAll
    static void initializeMyBatisPlusMetadata() {
        MybatisConfiguration configuration =
                new MybatisConfiguration();

        MapperBuilderAssistant messageLogAssistant =
                new MapperBuilderAssistant(
                        configuration,
                        "OrderTimeoutConsumerTest.OrderMessageLog"
                );

        messageLogAssistant.setCurrentNamespace(
                OrderMessageLogMapper.class.getName()
        );

        TableInfoHelper.initTableInfo(
                messageLogAssistant,
                OrderMessageLog.class
        );

        MapperBuilderAssistant orderAssistant =
                new MapperBuilderAssistant(
                        configuration,
                        "OrderTimeoutConsumerTest.OrderInfo"
                );

        orderAssistant.setCurrentNamespace(
                OrderMapper.class.getName()
        );

        TableInfoHelper.initTableInfo(
                orderAssistant,
                OrderInfo.class
        );
    }

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private InventoryService inventoryService;

    @Mock
    private OrderMessageLogMapper messageLogMapper;

    private ObjectMapper objectMapper;
    private OrderTimeoutConsumer consumer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();

        consumer = new OrderTimeoutConsumer(
                orderMapper,
                inventoryService,
                messageLogMapper,
                objectMapper
        );

        when(messageLogMapper.update(
                isNull(),
                any(LambdaUpdateWrapper.class)
        )).thenReturn(1);
    }

    @Test
    void normalJsonObjectPayloadProcessesTimeout() {
        when(orderMapper.markTimeoutIfExpired(ORDER_NO))
                .thenReturn(1);

        consumer.onOrderTimeout(
                message(validPayload())
        );

        verify(orderMapper).markTimeoutIfExpired(ORDER_NO);
        verify(inventoryService)
                .rollbackStock(PRODUCT_ID, QUANTITY);

        // 第一次把消息设为 CONSUMING，第二次设为 CONSUMED
        verify(messageLogMapper, times(2))
                .update(
                        isNull(),
                        any(LambdaUpdateWrapper.class)
                );
    }

    @Test
    void legacyDoubleEncodedPayloadIsStillAccepted()
            throws Exception {

        when(orderMapper.markTimeoutIfExpired(ORDER_NO))
                .thenReturn(1);

        String legacyPayload =
                objectMapper.writeValueAsString(validPayload());

        consumer.onOrderTimeout(
                message(legacyPayload)
        );

        verify(orderMapper).markTimeoutIfExpired(ORDER_NO);
        verify(inventoryService)
                .rollbackStock(PRODUCT_ID, QUANTITY);
    }

    @Test
    void paidOrderIgnoresTimeoutWithoutRestoringStock() {
        OrderInfo paidOrder = orderWithStatus(OrderStatus.PAID);

        when(orderMapper.markTimeoutIfExpired(ORDER_NO))
                .thenReturn(0);

        when(orderMapper.selectOne(
                any(LambdaQueryWrapper.class)
        )).thenReturn(paidOrder);

        consumer.onOrderTimeout(
                message(validPayload())
        );

        verify(inventoryService, never())
                .rollbackStock(PRODUCT_ID, QUANTITY);

        verify(messageLogMapper, times(2))
                .update(
                        isNull(),
                        any(LambdaUpdateWrapper.class)
                );
    }

    @Test
    void earlyTimeoutForPendingOrderRequestsRetry() {
        OrderInfo pendingOrder =
                orderWithStatus(OrderStatus.PENDING);

        when(orderMapper.markTimeoutIfExpired(ORDER_NO))
                .thenReturn(0);

        when(orderMapper.selectOne(
                any(LambdaQueryWrapper.class)
        )).thenReturn(pendingOrder);

        RuntimeException exception = assertThrows(
                RuntimeException.class,
                () -> consumer.onOrderTimeout(
                        message(validPayload())
                )
        );

        assertInstanceOf(
                IllegalStateException.class,
                exception.getCause()
        );

        verify(inventoryService, never())
                .rollbackStock(PRODUCT_ID, QUANTITY);
    }

    private Message message(String payload) {
        MessageProperties properties =
                new MessageProperties();

        properties.setHeader(
                "messageId",
                MESSAGE_ID
        );

        return new Message(
                payload.getBytes(StandardCharsets.UTF_8),
                properties
        );
    }

    private String validPayload() {
        return """
                {
                  "orderNo": "ORD-TIMEOUT-001",
                  "productId": 1,
                  "quantity": 2
                }
                """;
    }

    private OrderInfo orderWithStatus(OrderStatus status) {
        OrderInfo order = new OrderInfo();
        order.setOrderNo(ORDER_NO);
        order.setProductId(PRODUCT_ID);
        order.setQuantity(QUANTITY);
        order.setStatus(status.getValue());
        order.setExpireTime(
                LocalDateTime.now().plusMinutes(5)
        );
        return order;
    }
}