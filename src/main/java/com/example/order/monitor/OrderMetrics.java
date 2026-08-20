package com.example.order.monitor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.Getter;
import org.springframework.stereotype.Component;

@Getter
@Component
public class OrderMetrics {

    private final Counter orderSuccessCounter;
    private final Counter orderFailCounter;
    private final Counter stockDeductFailCounter;
    private final Counter idempotentRejectCounter;
    private final Counter messageRetryCounter;
    private final Timer orderCreateTimer;

    public OrderMetrics(MeterRegistry registry) {
        this.orderSuccessCounter = Counter.builder("order.create.success")
                .description("Number of successful order creations")
                .register(registry);

        this.orderFailCounter = Counter.builder("order.create.fail")
                .description("Number of failed order creations")
                .register(registry);

        this.stockDeductFailCounter = Counter.builder("stock.deduct.fail")
                .description("Number of stock deduction failures")
                .register(registry);

        this.idempotentRejectCounter = Counter.builder("idempotent.reject")
                .description("Number of rejected duplicate requests")
                .register(registry);

        this.messageRetryCounter = Counter.builder("message.retry")
                .description("Number of message retries")
                .register(registry);

        this.orderCreateTimer = Timer.builder("order.create.duration")
                .description("Time taken to create an order")
                .register(registry);
    }
}