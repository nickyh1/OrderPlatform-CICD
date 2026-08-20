package com.example.order.mq;

import com.example.order.config.RabbitMQConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DeadLetterConsumer {

    @RabbitListener(queues = RabbitMQConfig.ORDER_DEAD_LETTER_QUEUE)
    public void onDeadLetter(String payload) {
        log.error("Dead letter received, manual investigation needed: {}", payload);
        // In production: send alert, write to alert table, notify on-call engineer
    }
}