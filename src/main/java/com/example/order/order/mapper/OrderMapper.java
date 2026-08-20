package com.example.order.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.order.order.entity.OrderInfo;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface OrderMapper extends BaseMapper<OrderInfo> {

    /**
     * Atomically transition order from PENDING to target status.
     * Returns 1 if updated, 0 if order was already in a terminal state (concurrent race lost).
     */
    @Update("UPDATE orders SET status = #{targetStatus}, update_time = NOW() " +
            "WHERE order_no = #{orderNo} AND status = 'PENDING'")
    int updateStatusFromPending(@Param("orderNo") String orderNo,
                                @Param("targetStatus") String targetStatus);

    /**
     * Atomically mark order as PAID from PENDING, recording payment timestamp.
     * Returns 1 if updated, 0 if already processed.
     */
    @Update("UPDATE orders SET status = 'PAID', payment_time = NOW(), update_time = NOW() " +
            "WHERE order_no = #{orderNo} AND status = 'PENDING'")
    int markPaidIfPending(@Param("orderNo") String orderNo);
}