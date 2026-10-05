package com.ruomu.xiaozhi.service;

import com.ruomu.xiaozhi.dto.AppointmentScheduleResponse;
import com.ruomu.xiaozhi.dto.AppointmentScheduleResponse.ScheduleItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Service
public class AppointmentScheduleService {

    private final JdbcTemplate jdbcTemplate;

    public AppointmentScheduleService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public AppointmentScheduleResponse findSchedules(
            String hospitalId,
            String department) {

        return findSchedulesAt(
                hospitalId,
                department,
                Instant.now()
        );
    }

    // 测试可传入固定时间；HTTP接口始终使用服务器当前时间。
    AppointmentScheduleResponse findSchedulesAt(
            String hospitalId,
            String department,
            Instant now) {

        LocalDate today = AppointmentBookingPolicy.businessDate(now);
        String id = hospitalId == null ? "" : hospitalId.strip();
        String dept = department == null ? "" : department.strip();

        if (id.isEmpty() || dept.isEmpty()) {
            return response(
                    "INVALID_INPUT",
                    id,
                    dept,
                    today,
                    List.of(),
                    "请提供医院编号和科室。"
            );
        }

        if (id.length() > 64 || dept.length() > 64) {
            return response(
                    "INVALID_INPUT",
                    id,
                    dept,
                    today,
                    List.of(),
                    "医院编号和科室分别不能超过64个字符。"
            );
        }

        List<ScheduleItem> items = jdbcTemplate.query("""
                SELECT s.visit_date, s.total_capacity,
                       COUNT(a.appointment_id) AS active_count
                FROM demo_appointment_schedules s
                LEFT JOIN demo_appointments a
                  ON a.hospital_id = s.hospital_id
                 AND a.department = s.department
                 AND a.visit_date = s.visit_date
                 AND a.status = 'DEMO_CREATED'
                WHERE s.hospital_id = ? AND s.department = ?
                  AND s.visit_date BETWEEN ? AND ?
                GROUP BY s.hospital_id, s.department,
                         s.visit_date, s.total_capacity
                ORDER BY s.visit_date
                """,
                (rs, rowNum) -> {
                    int capacity = rs.getInt("total_capacity");
                    long activeCount = rs.getLong("active_count");
                    LocalDate date = rs.getDate("visit_date").toLocalDate();

                    long remaining = Math.max(
                            0L,
                            capacity - activeCount
                    );

                    String bookingStatus =
                            !AppointmentBookingPolicy.isReleased(date, now)
                                    ? "NOT_RELEASED"
                                    : remaining == 0
                                    ? "FULL"
                                    : "AVAILABLE";

                    return new ScheduleItem(
                            date,
                            capacity,
                            activeCount,
                            remaining,
                            AppointmentBookingPolicy.releaseAt(date)
                                    .toOffsetDateTime()
                                    .toString(),
                            bookingStatus,
                            "AVAILABLE".equals(bookingStatus)
                    );
                },
                id,
                dept,
                Date.valueOf(today.plusDays(1)),
                Date.valueOf(
                        today.plusDays(AppointmentBookingPolicy.ADVANCE_DAYS)
                )
        );

        if (items.isEmpty()) {
            return response(
                    "NO_DATA",
                    id,
                    dept,
                    today,
                    items,
                    "本地未配置该医院、科室在未来"
                            + AppointmentBookingPolicy.ADVANCE_DAYS
                            + "天的演示排班。"
                            + "这不代表真实医院不存在或已经满额。"
            );
        }

        return response(
                "DEMO_DATA",
                id,
                dept,
                today,
                items,
                "仅为本地虚构演示排班。"
                        + "参考剩余数量=max(总名额-未取消预约数,0)。"
                        + "每个就诊日期提前"
                        + AppointmentBookingPolicy.ADVANCE_DAYS
                        + "天、上海时间"
                        + AppointmentBookingPolicy.RELEASE_TIME
                        + "开放。"
                        + "NOT_RELEASED为未放号，即使参考剩余大于0也不能确认；"
                        + "FULL为满额。"
                        + "AVAILABLE和bookable=true仅代表查询时可申请，"
                        + "查询不预留名额。"
                        + "草稿不占名额，确认时由后台再次校验放号时间与名额。"
                        + "查询不锁定名额，不保证能够预约，不代表真实医院号源。"
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
                AppointmentBookingPolicy.ZONE.getId(),
                true,
                List.copyOf(items),
                message
        );
    }
}