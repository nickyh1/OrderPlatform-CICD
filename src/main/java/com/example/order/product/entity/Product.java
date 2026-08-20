package com.example.order.product.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("product")
public class Product {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private BigDecimal price;

    private String description;

    /**
     * 1-上架 0-下架
     */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}