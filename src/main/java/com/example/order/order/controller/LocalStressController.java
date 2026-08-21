package com.example.order.order.controller;

import com.example.order.common.BusinessException;
import com.example.order.common.IdempotentService;
import com.example.order.common.RateLimitService;
import com.example.order.common.Result;
import com.example.order.common.ResultCode;
import com.example.order.order.entity.CreateOrderRequest;
import com.example.order.order.entity.OrderInfo;
import com.example.order.order.entity.StressOrderRequest;
import com.example.order.order.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("local")
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class LocalStressController {

    private final OrderService orderService;
    private final IdempotentService idempotentService;
    private final RateLimitService rateLimitService;

    @PostMapping("/stress-test")
    public Result<OrderInfo> stressTestOrder(
            @Valid @RequestBody StressOrderRequest request) {

        String rateLimitKey =
                "rate_limit:order:" + request.getUserId();

        if (!rateLimitService.isAllowed(rateLimitKey, 100, 1)) {
            throw new BusinessException(ResultCode.RATE_LIMITED);
        }

        CreateOrderRequest createOrderRequest =
                new CreateOrderRequest();

        createOrderRequest.setUserId(request.getUserId());
        createOrderRequest.setProductId(request.getProductId());
        createOrderRequest.setQuantity(request.getQuantity());
        createOrderRequest.setIdempotentToken(
                idempotentService.generateToken()
        );

        return Result.success(
                orderService.createOrder(createOrderRequest)
        );
    }
}