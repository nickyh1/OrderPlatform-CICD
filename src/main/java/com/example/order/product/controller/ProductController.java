package com.example.order.product.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.order.common.Result;
import com.example.order.inventory.entity.Inventory;
import com.example.order.inventory.service.InventoryService;
import com.example.order.product.entity.Product;
import com.example.order.product.entity.UpdateProductRequest;
import com.example.order.product.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;
    private final InventoryService inventoryService;

    @GetMapping
    public Result<Page<Product>> list(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return Result.success(productService.listProducts(pageNum, pageSize));
    }

    /**
     * Get product detail (with cache)
     */
    @GetMapping("/{id}")
    public Result<Product> detail(@PathVariable Long id) {
        return Result.success(productService.getByIdWithCache(id));
    }

    @GetMapping("/{id}/stock")
    public Result<Inventory> stock(@PathVariable Long id) {
        return Result.success(inventoryService.getByProductId(id));
    }

    /**
     * Update product (with delayed double delete for cache consistency)
     */
    @PutMapping
    public Result<Product> update(@Valid @RequestBody UpdateProductRequest request) {
        return Result.success(productService.updateProduct(request));
    }
}