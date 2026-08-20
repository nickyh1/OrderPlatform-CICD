package com.example.order.inventory.service;

import com.example.order.common.BusinessException;
import com.example.order.common.ResultCode;
import com.example.order.inventory.mapper.InventoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DbOptimisticLockStrategy implements StockDeductStrategy {

    private final InventoryMapper inventoryMapper;

    @Override
    public void deduct(Long productId, int quantity) {
        int rows = inventoryMapper.deductStock(productId, quantity);
        if (rows == 0) {
            throw new BusinessException(ResultCode.STOCK_NOT_ENOUGH);
        }
        log.info("[DB-Lock] Stock deducted: productId={}, quantity={}", productId, quantity);
    }

    @Override
    public void rollback(Long productId, int quantity) {
        int rows = inventoryMapper.rollbackStock(productId, quantity);
        if (rows == 0) {
            throw new BusinessException(ResultCode.STOCK_ROLLBACK_FAILED);
        }
        log.info("[DB-Lock] Stock rolled back: productId={}, quantity={}", productId, quantity);
    }

    @Override
    public void confirm(Long productId, int quantity) {
        int rows = inventoryMapper.confirmStock(productId, quantity);
        if (rows == 0) {
            throw new BusinessException(ResultCode.STOCK_CONFIRM_FAILED);
        }
        log.info("[DB-Lock] Stock confirmed: productId={}, quantity={}", productId, quantity);
    }

    @Override
    public String name() {
        return "DB_OPTIMISTIC_LOCK";
    }
}