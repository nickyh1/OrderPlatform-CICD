package com.example.order;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.order.common.IdempotentService;
import com.example.order.inventory.entity.Inventory;
import com.example.order.inventory.mapper.InventoryMapper;
import com.example.order.inventory.service.InventoryService;
import com.example.order.inventory.service.StockCacheService;
import com.example.order.order.entity.CreateOrderRequest;
import com.example.order.order.entity.OrderInfo;
import com.example.order.order.mapper.OrderMapper;
import com.example.order.order.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class ConcurrentOrderTest {

    @Autowired private OrderService orderService;
    @Autowired private InventoryService inventoryService;
    @Autowired private InventoryMapper inventoryMapper;
    @Autowired private StockCacheService stockCacheService;
    @Autowired private OrderMapper orderMapper;
    @Autowired private IdempotentService idempotentService;

    /**
     * Test idempotent: same token, 20 concurrent requests, exactly 1 should succeed.
     */
    @Test
    void testIdempotent() throws InterruptedException {
        Long productId = 1L;

        // Ensure enough stock for this test (at least 1 unit)
        inventoryMapper.update(null, new LambdaUpdateWrapper<Inventory>()
                .eq(Inventory::getProductId, productId)
                .set(Inventory::getAvailableStock, 100)
                .set(Inventory::getLockedStock, 0)
                .set(Inventory::getTotalStock, 100));
        stockCacheService.setStock(productId, 100);

        String token = idempotentService.generateToken();

        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    CreateOrderRequest request = new CreateOrderRequest();
                    request.setUserId(1L);
                    request.setProductId(productId);
                    request.setQuantity(1);
                    request.setIdempotentToken(token);
                    orderService.createOrder(request);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        done.await();
        executor.shutdown();

        System.out.println("========== IDEMPOTENT TEST RESULT ==========");
        System.out.println("Token:            " + token);
        System.out.println("Total threads:    " + threadCount);
        System.out.println("Success orders:   " + successCount.get());
        System.out.println("Failed orders:    " + failCount.get());
        System.out.println("=============================================");

        assertEquals(1, successCount.get(), "Idempotent token must produce exactly 1 successful order");
        assertEquals(threadCount - 1, failCount.get(), "All remaining requests must be rejected");
    }

    /**
     * Oversell prevention test: 50 threads competing for 5 items.
     * Setup is automatic — inventory is reset before the test runs.
     */
    @Test
    void testOversell() throws InterruptedException {
        Long productId = 5L;
        int initialStock = 5;
        int threadCount = 50;

        // Auto-setup: reset inventory and clear orders for product 5
        inventoryMapper.update(null, new LambdaUpdateWrapper<Inventory>()
                .eq(Inventory::getProductId, productId)
                .set(Inventory::getAvailableStock, initialStock)
                .set(Inventory::getLockedStock, 0)
                .set(Inventory::getTotalStock, initialStock));
        stockCacheService.setStock(productId, initialStock);
        orderMapper.delete(new LambdaQueryWrapper<OrderInfo>()
                .eq(OrderInfo::getProductId, productId));

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final long userId = 100 + i;
            executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    String token = idempotentService.generateToken();
                    CreateOrderRequest request = new CreateOrderRequest();
                    request.setUserId(userId);
                    request.setProductId(productId);
                    request.setQuantity(1);
                    request.setIdempotentToken(token);
                    orderService.createOrder(request);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        done.await();
        executor.shutdown();

        Inventory inventory = inventoryService.getByProductId(productId);

        System.out.println("========== OVERSELL TEST RESULT ==========");
        System.out.println("Initial stock:    " + initialStock);
        System.out.println("Total threads:    " + threadCount);
        System.out.println("Success orders:   " + successCount.get());
        System.out.println("Failed orders:    " + failCount.get());
        System.out.println("Available stock:  " + inventory.getAvailableStock());
        System.out.println("Locked stock:     " + inventory.getLockedStock());
        System.out.println("Total stock:      " + inventory.getTotalStock());
        System.out.println("==========================================");

        assertTrue(successCount.get() <= initialStock,
                "Successful orders (" + successCount.get() + ") must not exceed initial stock (" + initialStock + ")");
        assertTrue(inventory.getAvailableStock() >= 0,
                "Available stock must not go negative");
        // Pending orders move stock from available to locked; total must be conserved
        assertEquals(initialStock, inventory.getAvailableStock() + inventory.getLockedStock(),
                "Stock accounting must balance: initial = available + locked (orders are still pending, not yet paid)");
        assertEquals(successCount.get(), inventory.getLockedStock(),
                "Each successful pending order must lock exactly one stock unit");
    }
}
