package com.ruomu.xiaozhi.service;

import com.ruomu.xiaozhi.dto.AppointmentScheduleResponse;
import com.ruomu.xiaozhi.dto.AppointmentScheduleResponse.ScheduleItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Service
public class AppointmentScheduleService {

    private static final ZoneId BUSINESS_ZONE =
            ZoneId.of("Asia/Shanghai");

    private final JdbcTemplate jdbcTemplate;

    public AppointmentScheduleService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public AppointmentScheduleResponse findSchedules(
            String hospitalId,
            String department) {

        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        String id = hospitalId == null ? "" : hospitalId.strip();
        String dept = department == null ? "" : department.strip();

        if (id.isEmpty() || dept.isEmpty()) {
            return response(
                    "INVALID_INPUT", id, dept, today, List.of(),
                    "请提供医院编号和科室。"
            );
        }

        if (id.length() > 64 || dept.length() > 64) {
            return response(
                    "INVALID_INPUT", id, dept, today, List.of(),
                    "医院编号和科室分别不能超过64个字符。"
            );
        }

        List<ScheduleItem> items = jdbcTemplate.query(
                """
                SELECT s.visit_date,
                       s.total_capacity,
                       COUNT(a.appointment_id) AS active_count
                FROM demo_appointment_schedules s
                LEFT JOIN demo_appointments a
                  ON a.hospital_id = s.hospital_id
                 AND a.department = s.department
                 AND a.visit_date = s.visit_date
                 AND a.status = 'DEMO_CREATED'
                WHERE s.hospital_id = ?
                  AND s.department = ?
                  AND s.visit_date BETWEEN ? AND ?
                GROUP BY s.hospital_id, s.department,
                         s.visit_date, s.total_capacity
                ORDER BY s.visit_date
                """,
                (rs, rowNum) -> {
                    int capacity = rs.getInt("total_capacity");
                    long activeCount = rs.getLong("active_count");

                    return new ScheduleItem(
                            rs.getDate("visit_date").toLocalDate(),
                            capacity,
                            activeCount,
                            Math.max(0L, capacity - activeCount)
                    );
                },
                id,
                dept,
                Date.valueOf(today.plusDays(1)),
                Date.valueOf(today.plusDays(3))
        );

        if (items.isEmpty()) {
            return response(
                    "NO_DATA", id, dept, today, items,
                    "本地未配置该医院、科室在未来三天的演示排班。"
                            + "这不代表真实医院不存在或已经满额。"
            );
        }

        return response(
                "DEMO_DATA", id, dept, today, items,
                "仅为本地虚构演示排班。"
                        + "参考剩余数量=max(总名额-未取消预约数,0)。"
                        + "草稿不占名额；确认时会检查剩余名额，满额则拒绝。"
                        + "尚未执行09:30放号控制。"
                        + "查询不锁定名额，不保证能够预约，"
                        + "不代表真实医院号源。"
        );
    }

    private AppointmentScheduleResponse response(
            String status,
            String hospitalId,
            String department,
            LocalDate today,
            List<ScheduleItem> items,
            String message) {

        return new AppointmentScheduleResponse(
                status,
                hospitalId,
                department,
                today,
                BUSINESS_ZONE.getId(),
                true,
                List.copyOf(items),
                message
        );
    }
}