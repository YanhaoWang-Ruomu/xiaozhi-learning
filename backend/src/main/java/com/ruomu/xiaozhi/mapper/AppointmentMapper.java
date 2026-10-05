package com.ruomu.xiaozhi.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ruomu.xiaozhi.entity.AppointmentEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AppointmentMapper extends BaseMapper<AppointmentEntity> {

    @Update("""
            UPDATE demo_appointments
            SET status = 'DEMO_CANCELLED', cancelled_at = #{cancelledAt}
            WHERE appointment_id = #{id} AND status = 'DEMO_CREATED'
            """)
    int cancelIfActive(
            @Param("id") String id,
            @Param("cancelledAt") String cancelledAt
    );
}