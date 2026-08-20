package com.example.order.product.entity;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class UpdateProductRequest {

    @NotNull(message = "productId is required")
    private Long id;

    private String name;

    private BigDecimal price;

    private String description;

    private Integer status;
}