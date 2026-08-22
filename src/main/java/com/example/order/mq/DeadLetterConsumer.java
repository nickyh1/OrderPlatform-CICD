package com.example.order.mq;

import com.example.order.config.RabbitMQConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
public class DeadLetterConsumer {

    @RabbitListener(
            queues = RabbitMQConfig.ORDER_DEAD_LETTER_QUEUE
    )
    public void onDeadLetter(Message message) {
        String messageId = (String) message
                .getMessageProperties()
                .getHeader("messageId");

        Object exceptionMessage = message
                .getMessageProperties()
                .getHeader(
                        RepublishMessageRecoverer.X_EXCEPTION_MESSAGE
                );

        String payload = new String(
                message.getBody(),
                StandardCharsets.UTF_8
        );

        /*
         * 这里暂时只记录报警信息，不重新抛出异常。
         *
         * 如果死信消费者再次抛出异常，全局 MessageRecoverer
         * 可能会把死信重新投递到同一个死信队列，形成循环。
         */
        log.error(
                "Dead letter received: " +
                        "messageId={}, exception={}, payload={}",
                messageId,
                exceptionMessage,
                payload
        );
    }
}