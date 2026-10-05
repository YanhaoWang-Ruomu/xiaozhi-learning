package com.ruomu.xiaozhi.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ruomu.xiaozhi.entity.AppointmentEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;

@Mapper
public interface AppointmentMapper
        extends BaseMapper<AppointmentEntity> {

    @Select("""
            SELECT total_capacity
            FROM demo_appointment_schedules
            WHERE hospital_id = #{hospitalId}
              AND department = #{department}
              AND visit_date = #{visitDate}
            FOR UPDATE
            """)
    Integer lockCapacity(
            @Param("hospitalId") String hospitalId,
            @Param("department") String department,
            @Param("visitDate") LocalDate visitDate
    );

    @Select("""
            SELECT COUNT(*)
            FROM demo_appointments
            WHERE hospital_id = #{hospitalId}
              AND department = #{department}
              AND visit_date = #{visitDate}
              AND status = 'DEMO_CREATED'
            """)
    long countActive(
            @Param("hospitalId") String hospitalId,
            @Param("department") String department,
            @Param("visitDate") LocalDate visitDate
    );

    @Update("""
            UPDATE demo_appointments
            SET status = 'DEMO_CANCELLED',
                cancelled_at = #{cancelledAt}
            WHERE appointment_id = #{id}
              AND status = 'DEMO_CREATED'
            """)
    int cancelIfActive(
            @Param("id") String id,
            @Param("cancelledAt") String cancelledAt
    );
}