package com.example.order.mq.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.order.mq.entity.OrderMessageLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrderMessageLogMapper extends BaseMapper<OrderMessageLog> {
}