package com.example.order.order.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.order.common.*;
import com.example.order.order.entity.CreateOrderRequest;
import com.example.order.order.entity.OrderInfo;
import com.example.order.order.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import com.example.order.common.RateLimitService;
import com.example.order.common.BusinessException;
import com.example.order.common.ResultCode;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final IdempotentService idempotentService;
    private final RateLimitService rateLimitService;

    /**
     * Stress test endpoint: auto-generate token and create order.
     * DO NOT use in production.
     */
    @PostMapping("/stress-test")
    public Result<OrderInfo> stressTestOrder(@Valid @RequestBody CreateOrderRequest request) {
        // Rate limit check
        String rateLimitKey = "rate_limit:order:" + request.getUserId();
        if (!rateLimitService.isAllowed(rateLimitKey, 100, 1)) {
            throw new BusinessException(ResultCode.RATE_LIMITED);
        }

        // Auto-generate token
        String token = idempotentService.generateToken();
        request.setIdempotentToken(token);
        return Result.success(orderService.createOrder(request));
    }



    /**
     * Get idempotent token before placing order
     */
    @GetMapping("/token")
    public Result<String> getToken() {
        return Result.success(idempotentService.generateToken());
    }

    @PostMapping
    public Result<OrderInfo> createOrder(@Valid @RequestBody CreateOrderRequest request) {
        // Rate limit: 5 requests per second per user
        String rateLimitKey = "rate_limit:order:" + request.getUserId();
        if (!rateLimitService.isAllowed(rateLimitKey, 5, 1)) {
            throw new BusinessException(ResultCode.RATE_LIMITED);
        }
        return Result.success(orderService.createOrder(request));
    }

    /**
     * My orders (paginated)
     */
    @GetMapping("/user/{userId}")
    public Result<Page<OrderInfo>> listUserOrders(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return Result.success(orderService.listUserOrders(userId, pageNum, pageSize));
    }

    /**
     * Order detail
     */
    @GetMapping("/{orderNo}")
    public Result<OrderInfo> detail(@PathVariable String orderNo) {
        return Result.success(orderService.getByOrderNo(orderNo));
    }

    /**
     * Cancel order
     */
    @PostMapping("/{orderNo}/cancel")
    public Result<OrderInfo> cancel(@PathVariable String orderNo) {
        return Result.success(orderService.cancelOrder(orderNo));
    }
}