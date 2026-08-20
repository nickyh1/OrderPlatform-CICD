package com.example.order.inventory.service;

import com.example.order.inventory.entity.Inventory;
import com.example.order.inventory.mapper.InventoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class StockCacheService implements CommandLineRunner {

    private final InventoryMapper inventoryMapper;
    private final StringRedisTemplate redisTemplate;

    private static final String STOCK_KEY_PREFIX = "stock:";

    /**
     * Warm up: sync all available_stock from DB to Redis on startup
     */
    @Override
    public void run(String... args) {
        List<Inventory> inventories = inventoryMapper.selectList(null);
        for (Inventory inv : inventories) {
            String key = STOCK_KEY_PREFIX + inv.getProductId();
            redisTemplate.opsForValue().set(key, String.valueOf(inv.getAvailableStock()));
            log.info("Stock cache warmed: productId={}, stock={}", inv.getProductId(), inv.getAvailableStock());
        }
        log.info("Stock cache warm-up completed, {} products loaded", inventories.size());
    }

    /**
     * Redis atomic deduct. Returns remaining stock after deduction.
     * If result < 0, stock not enough — caller must INCR back.
     */
    public long deduct(Long productId, int quantity) {
        String key = STOCK_KEY_PREFIX + productId;
        Long remaining = redisTemplate.opsForValue().decrement(key, quantity);
        if (remaining == null) {
            return -1;
        }
        return remaining;
    }

    /**
     * Rollback Redis stock (compensate on DB failure or order cancel/timeout)
     */
    public void rollback(Long productId, int quantity) {
        String key = STOCK_KEY_PREFIX + productId;
        redisTemplate.opsForValue().increment(key, quantity);
        log.info("Redis stock rolled back: productId={}, quantity={}", productId, quantity);
    }

    /**
     * Manually set stock (for testing)
     */
    public void setStock(Long productId, int stock) {
        String key = STOCK_KEY_PREFIX + productId;
        redisTemplate.opsForValue().set(key, String.valueOf(stock));
    }

    /**
     * Get current Redis stock
     */
    public int getStock(Long productId) {
        String key = STOCK_KEY_PREFIX + productId;
        String val = redisTemplate.opsForValue().get(key);
        return val == null ? 0 : Integer.parseInt(val);
    }
}