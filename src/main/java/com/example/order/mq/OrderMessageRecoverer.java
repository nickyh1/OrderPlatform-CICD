package com.example.order.mq;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.order.config.RabbitMQConfig;
import com.example.order.mq.entity.OrderMessageLog;
import com.example.order.mq.mapper.OrderMessageLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Component
public class OrderMessageRecoverer implements MessageRecoverer {

    private final OrderMessageLogMapper messageLogMapper;
    private final RepublishMessageRecoverer republisher;

    public OrderMessageRecoverer(
            OrderMessageLogMapper messageLogMapper,
            RabbitTemplate rabbitTemplate
    ) {
        this.messageLogMapper = messageLogMapper;

        this.republisher = new RepublishMessageRecoverer(
                rabbitTemplate,
                RabbitMQConfig.ORDER_DLX_EXCHANGE,
                RabbitMQConfig.RK_ORDER_DEAD
        );
    }

    @Override
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            rollbackFor = Exception.class
    )
    public void recover(
            Message message,
            Throwable cause
    ) {
        String messageId = (String) message
                .getMessageProperties()
                .getHeader("messageId");

        int updatedRows = 0;

        if (messageId != null) {
            /*
             * 监听器的事务此时已经回滚完成，因此可以在新事务中
             * 安全地把消息标记为 FAILED。
             *
             * 不允许用 FAILED 覆盖已经成功消费的 CONSUMED。
             */
            updatedRows = messageLogMapper.update(
                    null,
                    new LambdaUpdateWrapper<OrderMessageLog>()
                            .eq(
                                    OrderMessageLog::getMessageId,
                                    messageId
                            )
                            .ne(
                                    OrderMessageLog::getStatus,
                                    "CONSUMED"
                            )
                            .set(
                                    OrderMessageLog::getStatus,
                                    "FAILED"
                            )
                            .set(
                                    OrderMessageLog::getNextRetryTime,
                                    LocalDateTime.now().plusSeconds(30)
                            )
            );
        }

        log.error(
                "Rabbit listener retries exhausted: " +
                        "messageId={}, updatedRows={}",
                messageId,
                updatedRows,
                cause
        );

        /*
         * 将原消息投递到：
         *
         * order.dlx.exchange
         *      ↓ order.dead
         * order.dead.letter.queue
         *
         * RepublishMessageRecoverer 还会添加原交换机、
         * 原路由键和异常信息等诊断 Header。
         */
        republisher.recover(message, cause);
    }
}