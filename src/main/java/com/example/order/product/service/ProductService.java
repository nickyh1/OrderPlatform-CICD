package com.example.order.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.order.common.BusinessException;
import com.example.order.common.ResultCode;
import com.example.order.product.entity.Product;
import com.example.order.product.entity.UpdateProductRequest;
import com.example.order.product.mapper.ProductMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductMapper productMapper;
    private final ProductCacheService productCacheService;

    /**
     * Get product with cache (for read-heavy scenarios)
     */
    public Product getByIdWithCache(Long id) {
        Product product = productCacheService.getProductWithCache(id);
        if (product == null) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }
        return product;
    }

    /**
     * Update product: update DB first, then delete cache.
     */
    @Transactional(rollbackFor = Exception.class)
    public Product updateProduct(UpdateProductRequest request) {
        Product product = getById(request.getId());

        if (request.getName() != null) {
            product.setName(request.getName());
        }
        if (request.getPrice() != null) {
            product.setPrice(request.getPrice());
        }
        if (request.getDescription() != null) {
            product.setDescription(request.getDescription());
        }
        if (request.getStatus() != null) {
            product.setStatus(request.getStatus());
        }

        productMapper.updateById(product);
        log.info("Product updated in DB: productId={}", product.getId());

        // Evict cache only after transaction commits to avoid stale DB + evicted cache mismatch
        final Long productId = product.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                productCacheService.evictCacheWithDelay(productId);
            }
        });

        return product;
    }

    /**
     * Paginated product list (only on-shelf products)
     */
    public Page<Product> listProducts(int pageNum, int pageSize) {
        Page<Product> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<Product>()
                .eq(Product::getStatus, 1)
                .orderByDesc(Product::getCreateTime);
        return productMapper.selectPage(page, wrapper);
    }

    /**
     * Get product by id, throw if not found
     */
    public Product getById(Long id) {
        Product product = productMapper.selectById(id);
        if (product == null) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }
        return product;
    }

    /**
     * Get product and verify it's on shelf
     */
    public Product getAvailableProduct(Long id) {
        Product product = getById(id);
        if (product.getStatus() != 1) {
            throw new BusinessException(ResultCode.PRODUCT_OFF_SHELF);
        }
        return product;
    }
}