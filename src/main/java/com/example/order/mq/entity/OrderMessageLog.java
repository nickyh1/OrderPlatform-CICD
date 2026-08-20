package com.example.order.mq.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("order_message_log")
public class OrderMessageLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String messageId;

    private Long orderId;

    /**
     * ORDER_CREATED / PAYMENT_CALLBACK / ORDER_TIMEOUT
     */
    private String messageType;

    /**
     * JSON payload
     */
    private String payload;

    /**
     * PENDING / SENT / CONSUMED / FAILED
     */
    private String status;

    private Integer retryCount;

    private Integer maxRetry;

    private LocalDateTime nextRetryTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}