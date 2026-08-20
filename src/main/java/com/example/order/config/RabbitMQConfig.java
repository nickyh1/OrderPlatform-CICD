package com.example.order.config;

import com.example.order.mq.MqConfirmCallback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class RabbitMQConfig {

    // ===== Exchanges =====
    public static final String ORDER_EXCHANGE = "order.exchange";
    public static final String ORDER_DLX_EXCHANGE = "order.dlx.exchange";

    // ===== Queues =====
    public static final String ORDER_CREATED_QUEUE = "order.created.queue";
    public static final String ORDER_PAYMENT_QUEUE = "order.payment.queue";

    // Delay queue: messages sit here for 15 min, then go to DLX
    public static final String ORDER_DELAY_QUEUE = "order.delay.queue";
    // DLX queue: timeout consumer listens here
    public static final String ORDER_TIMEOUT_QUEUE = "order.timeout.queue";

    // Dead letter queue for failed messages
    public static final String ORDER_DEAD_LETTER_QUEUE = "order.dead.letter.queue";

    // ===== Routing Keys =====
    public static final String RK_ORDER_CREATED = "order.created";
    public static final String RK_ORDER_PAYMENT = "order.payment";
    public static final String RK_ORDER_TIMEOUT = "order.timeout";
    public static final String RK_ORDER_DEAD = "order.dead";

    // Delay: 15 minutes in milliseconds
    public static final int ORDER_TIMEOUT_MS = 15 * 60 * 1000;

    // ===== JSON message converter =====
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }

    // ===== RabbitTemplate with publisher confirm + returns =====
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         MqConfirmCallback confirmCallback) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());
        template.setConfirmCallback(confirmCallback);
        template.setReturnsCallback(confirmCallback);
        template.setMandatory(true);
        return template;
    }

    // ===== Main exchange =====
    @Bean
    public DirectExchange orderExchange() {
        return new DirectExchange(ORDER_EXCHANGE);
    }

    // ===== DLX exchange =====
    @Bean
    public DirectExchange orderDlxExchange() {
        return new DirectExchange(ORDER_DLX_EXCHANGE);
    }

    // ===== Order created queue =====
    @Bean
    public Queue orderCreatedQueue() {
        return QueueBuilder.durable(ORDER_CREATED_QUEUE)
                .withArgument("x-dead-letter-exchange", ORDER_DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", RK_ORDER_DEAD)
                .build();
    }

    @Bean
    public Binding orderCreatedBinding() {
        return BindingBuilder.bind(orderCreatedQueue())
                .to(orderExchange()).with(RK_ORDER_CREATED);
    }

    // ===== Payment callback queue =====
    @Bean
    public Queue orderPaymentQueue() {
        return QueueBuilder.durable(ORDER_PAYMENT_QUEUE)
                .withArgument("x-dead-letter-exchange", ORDER_DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", RK_ORDER_DEAD)
                .build();
    }

    @Bean
    public Binding orderPaymentBinding() {
        return BindingBuilder.bind(orderPaymentQueue())
                .to(orderExchange()).with(RK_ORDER_PAYMENT);
    }

    // ===== Delay queue (TTL + DLX = delayed message) =====
    @Bean
    public Queue orderDelayQueue() {
        return QueueBuilder.durable(ORDER_DELAY_QUEUE)
                .withArgument("x-dead-letter-exchange", ORDER_DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", RK_ORDER_TIMEOUT)
                .withArgument("x-message-ttl", ORDER_TIMEOUT_MS)
                .build();
    }

    @Bean
    public Binding orderDelayBinding() {
        return BindingBuilder.bind(orderDelayQueue())
                .to(orderExchange()).with("order.delay");
    }

    // ===== Timeout queue (DLX consumer listens here) =====
    @Bean
    public Queue orderTimeoutQueue() {
        return QueueBuilder.durable(ORDER_TIMEOUT_QUEUE).build();
    }

    @Bean
    public Binding orderTimeoutBinding() {
        return BindingBuilder.bind(orderTimeoutQueue())
                .to(orderDlxExchange()).with(RK_ORDER_TIMEOUT);
    }

    // ===== Dead letter queue (failed messages end up here) =====
    @Bean
    public Queue orderDeadLetterQueue() {
        return QueueBuilder.durable(ORDER_DEAD_LETTER_QUEUE).build();
    }

    @Bean
    public Binding orderDeadLetterBinding() {
        return BindingBuilder.bind(orderDeadLetterQueue())
                .to(orderDlxExchange()).with(RK_ORDER_DEAD);
    }
}