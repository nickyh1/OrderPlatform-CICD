package com.example.order.inventory.service;

import com.example.order.common.BusinessException;
import com.example.order.common.ResultCode;
import com.example.order.inventory.mapper.InventoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisPreDeductStrategy implements StockDeductStrategy {

    private final StockCacheService stockCacheService;
    private final InventoryMapper inventoryMapper;

    /**
     * Only rollback Redis, not DB (DB already rolled back by transaction).
     */
    public void compensateRedisOnly(Long productId, int quantity) {
        stockCacheService.rollback(productId, quantity);
        log.info("[Redis-Pre] Redis compensated after transaction rollback: productId={}, quantity={}", productId, quantity);
    }

    @Override
    public void deduct(Long productId, int quantity) {
        // Step 1: Redis atomic deduct
        long remaining = stockCacheService.deduct(productId, quantity);

        if (remaining < 0) {
            // Stock not enough — rollback Redis and reject
            stockCacheService.rollback(productId, quantity);
            throw new BusinessException(ResultCode.STOCK_NOT_ENOUGH);
        }

        // Step 2: DB deduct (within transaction)
        try {
            int rows = inventoryMapper.deductStock(productId, quantity);
            if (rows == 0) {
                // DB deduct failed — rollback Redis
                stockCacheService.rollback(productId, quantity);
                throw new BusinessException(ResultCode.STOCK_NOT_ENOUGH);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            // Any unexpected DB error — rollback Redis
            stockCacheService.rollback(productId, quantity);
            throw e;
        }

        log.info("[Redis-Pre] Stock deducted: productId={}, quantity={}, redisRemaining={}",
                productId, quantity, remaining);
    }

    @Override
    public void rollback(Long productId, int quantity) {
        // DB rollback inside transaction
        int rows = inventoryMapper.rollbackStock(productId, quantity);
        if (rows == 0) {
            throw new BusinessException(ResultCode.STOCK_ROLLBACK_FAILED);
        }
        log.info("[Redis-Pre] DB stock rolled back: productId={}, quantity={}", productId, quantity);

        // Redis INCR deferred to afterCommit: if DB transaction rolls back, Redis stays unchanged
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    stockCacheService.rollback(productId, quantity);
                    log.info("[Redis-Pre] Redis stock restored after commit: productId={}, quantity={}", productId, quantity);
                }
            });
        } else {
            stockCacheService.rollback(productId, quantity);
        }
    }

    @Override
    public void confirm(Long productId, int quantity) {
        // Confirm DB only (Redis stock already deducted, no need to change)
        int rows = inventoryMapper.confirmStock(productId, quantity);
        if (rows == 0) {
            throw new BusinessException(ResultCode.STOCK_CONFIRM_FAILED);
        }
        log.info("[Redis-Pre] Stock confirmed: productId={}, quantity={}", productId, quantity);
    }

    @Override
    public String name() {
        return "REDIS_PRE_DEDUCT";
    }
}