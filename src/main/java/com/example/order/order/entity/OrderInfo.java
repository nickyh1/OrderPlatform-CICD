package com.example.order.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("orders")
public class OrderInfo {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String orderNo;

    private Long userId;

    private Long productId;

    private Integer quantity;

    private BigDecimal totalAmount;

    /**
     * PENDING / PAID / CANCELLED / TIMEOUT
     */
    private String status;

    private String idempotentKey;

    private LocalDateTime paymentTime;

    private LocalDateTime expireTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}