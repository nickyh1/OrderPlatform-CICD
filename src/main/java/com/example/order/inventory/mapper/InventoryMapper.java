package com.example.order.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.order.inventory.entity.Inventory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface InventoryMapper extends BaseMapper<Inventory> {

    /**
     * Deduct stock (simple version, no optimistic lock yet)
     * Returns affected rows: 0 means stock not enough
     */
    @Update("UPDATE inventory SET available_stock = available_stock - #{quantity}, " +
            "locked_stock = locked_stock + #{quantity}, " +
            "update_time = NOW() " +
            "WHERE product_id = #{productId} AND available_stock >= #{quantity}")
    int deductStock(@Param("productId") Long productId, @Param("quantity") int quantity);

    /**
     * Rollback stock (cancel or timeout)
     */
    @Update("UPDATE inventory SET available_stock = available_stock + #{quantity}, " +
            "locked_stock = locked_stock - #{quantity}, " +
            "update_time = NOW() " +
            "WHERE product_id = #{productId} AND locked_stock >= #{quantity}")
    int rollbackStock(@Param("productId") Long productId, @Param("quantity") int quantity);

    /**
     * Confirm stock (payment success: locked -> sold)
     */
    @Update("UPDATE inventory SET locked_stock = locked_stock - #{quantity}, " +
            "total_stock = total_stock - #{quantity}, " +
            "update_time = NOW() " +
            "WHERE product_id = #{productId} AND locked_stock >= #{quantity}")
    int confirmStock(@Param("productId") Long productId, @Param("quantity") int quantity);

    /**
     * UNSAFE: deduct stock without atomic check (for oversell demo only)
     */
    @Update("UPDATE inventory SET available_stock = #{newStock}, " +
            "locked_stock = locked_stock + #{quantity}, " +
            "update_time = NOW() " +
            "WHERE product_id = #{productId}")
    int unsafeDeductStock(@Param("productId") Long productId,
                          @Param("newStock") int newStock,
                          @Param("quantity") int quantity);
}