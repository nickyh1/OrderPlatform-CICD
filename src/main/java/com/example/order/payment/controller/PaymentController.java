package com.example.order.payment.controller;

import com.example.order.common.Result;
import com.example.order.order.entity.OrderInfo;
import com.example.order.payment.entity.PaymentCallbackRequest;
import com.example.order.payment.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payment")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    /**
     * Simulate payment callback (normally called by payment gateway)
     */
    @PostMapping("/callback")
    public Result<OrderInfo> callback(@Valid @RequestBody PaymentCallbackRequest request) {
        OrderInfo order = paymentService.handleCallback(request.getOrderNo(), request.getSuccess());
        return Result.success(order);
    }
}