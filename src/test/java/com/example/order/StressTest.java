package com.example.order;

import com.example.order.common.IdempotentService;
import com.example.order.inventory.entity.Inventory;
import com.example.order.inventory.service.InventoryService;
import com.example.order.order.entity.CreateOrderRequest;
import com.example.order.order.service.OrderService;
import com.example.order.monitor.OrderMetrics;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@Disabled("Manual stress test — reset inventory and warm Redis cache before running; not for CI")
@SpringBootTest
public class StressTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private IdempotentService idempotentService;

    @Autowired
    private OrderMetrics orderMetrics;

    /**
     * Scenario 1: 100 users grab 50 items.
     * Before running:
     *   UPDATE inventory SET available_stock=50, locked_stock=0, total_stock=50, version=0 WHERE product_id=1;
     *   DELETE FROM orders WHERE product_id=1;
     *   Restart app to warm Redis cache.
     */
    @Test
    void scenario1_100ConcurrentGrab50Stock() throws InterruptedException {
        Long productId = 1L;
        int threadCount = 100;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < threadCount; i++) {
            final long userId = 200 + i;
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

        long elapsed = System.currentTimeMillis() - startTime;
        Inventory inventory = inventoryService.getByProductId(productId);

        System.out.println("========== SCENARIO 1: 100 CONCURRENT GRAB 50 STOCK ==========");
        System.out.println("Total threads:      " + threadCount);
        System.out.println("Success orders:     " + successCount.get());
        System.out.println("Failed orders:      " + failCount.get());
        System.out.println("Available stock:    " + inventory.getAvailableStock());
        System.out.println("Locked stock:       " + inventory.getLockedStock());
        System.out.println("Total stock:        " + inventory.getTotalStock());
        System.out.println("Total time (ms):    " + elapsed);
        System.out.println("Throughput (req/s):  " + (threadCount * 1000L / elapsed));
        System.out.println("Oversell:           " + (successCount.get() > 50 ? "YES!!!" : "NO"));
        System.out.println("==============================================================");
    }

    /**
     * Scenario 2: 500 concurrent orders.
     * Before running:
     *   UPDATE inventory SET available_stock=200, locked_stock=0, total_stock=200, version=0 WHERE product_id=3;
     *   DELETE FROM orders WHERE product_id=3;
     *   Restart app to warm Redis cache.
     */
    @Test
    void scenario2_500ConcurrentOrders() throws InterruptedException {
        Long productId = 3L;
        int threadCount = 200;

        ExecutorService executor = Executors.newFixedThreadPool(200); // 100 thread pool
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < threadCount; i++) {
            final long userId = 500 + i;
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

        long elapsed = System.currentTimeMillis() - startTime;
        Inventory inventory = inventoryService.getByProductId(productId);

        System.out.println("========== SCENARIO 2: 500 CONCURRENT ORDERS ==========");
        System.out.println("Total threads:      " + threadCount);
        System.out.println("Success orders:     " + successCount.get());
        System.out.println("Failed orders:      " + failCount.get());
        System.out.println("Available stock:    " + inventory.getAvailableStock());
        System.out.println("Locked stock:       " + inventory.getLockedStock());
        System.out.println("Total stock:        " + inventory.getTotalStock());
        System.out.println("Total time (ms):    " + elapsed);
        System.out.println("Throughput (req/s):  " + (threadCount * 1000L / elapsed));
        System.out.println("==============================================================");
    }
}