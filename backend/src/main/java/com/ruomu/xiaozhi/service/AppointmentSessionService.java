package com.ruomu.xiaozhi.service;

import com.ruomu.xiaozhi.dto.AppointmentSessionResponse;
import com.ruomu.xiaozhi.dto.AppointmentSessionResponse.SessionItem;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

@Service
public class AppointmentSessionService {

    // 明确的虚构演示班次，不自动给新加入目录的所有医生安排出诊。
    private static final List<String> DOCTORS = List.of("DOC001", "DOC002");
    private static final List<String> SLOTS = List.of("AM", "PM");
    private static final int DEFAULT_CAPACITY = 5;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public AppointmentSessionService(DataSource dataSource) {
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(15);
    }

    public MaintenanceResult ensureUpcomingSessions() {
        return ensureAt(Instant.now());
    }

    // 固定时间仅供同包测试，不提供修改服务器业务日期的HTTP接口。
    MaintenanceResult ensureAt(Instant now) {
        LocalDate today = AppointmentBookingPolicy.businessDate(now);
        LocalDate from = today.plusDays(1);
        LocalDate to = today.plusDays(AppointmentBookingPolicy.ADVANCE_DAYS);

        return Objects.requireNonNull(tx.execute(status -> {
            for (int day = 1; day <= AppointmentBookingPolicy.ADVANCE_DAYS; day++) {
                for (String doctor : DOCTORS) {
                    for (String slot : SLOTS) {
                        jdbc.update("""
                                INSERT INTO demo_appointment_sessions
                                    (hospital_id, department, visit_date,
                                     doctor_id, slot_id, total_capacity)
                                SELECT d.hospital_id, d.department, ?,
                                       d.doctor_id, t.slot_id, ?
                                FROM demo_appointment_doctors d
                                JOIN demo_appointment_time_slots t
                                  ON t.hospital_id = d.hospital_id
                                 AND t.department = d.department
                                WHERE d.hospital_id = ? AND d.department = ?
                                  AND d.doctor_id = ? AND t.slot_id = ?
                                  AND d.enabled = 1 AND t.enabled = 1
                                ON DUPLICATE KEY UPDATE
                                    total_capacity = demo_appointment_sessions.total_capacity
                                """, Date.valueOf(today.plusDays(day)), DEFAULT_CAPACITY,
                                AppointmentBookingPolicy.HOSPITAL_ID,
                                AppointmentBookingPolicy.DEPARTMENT, doctor, slot);
                    }
                }
            }
            Integer count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM demo_appointment_sessions
                    WHERE hospital_id = ? AND department = ?
                      AND visit_date BETWEEN ? AND ?
                    """, Integer.class,
                    AppointmentBookingPolicy.HOSPITAL_ID,
                    AppointmentBookingPolicy.DEPARTMENT,
                    Date.valueOf(from), Date.valueOf(to));
            // 当前窗口内已配置的总行数，不是本次新增数；包括已停用目录的历史配置。
            return new MaintenanceResult(today, from, to, Objects.requireNonNull(count));
        }));
    }

    public AppointmentSessionResponse findSessions(String hospitalId, String department) {
        return findAt(hospitalId, department, Instant.now());
    }

    AppointmentSessionResponse findAt(String hospitalId, String department, Instant now) {
        String id = requireText(hospitalId, "hospitalId");
        String dept = requireText(department, "department");
        LocalDate today = AppointmentBookingPolicy.businessDate(now);

        List<SessionItem> items = jdbc.query("""
                SELECT s.session_id, s.visit_date, s.doctor_id, d.doctor_name,
                       s.slot_id, t.slot_name, t.start_time, t.end_time,
                       s.total_capacity, ds.total_capacity AS daily_capacity,
                       (SELECT COUNT(*) FROM demo_appointment_session_bookings b
                        JOIN demo_appointments a ON a.appointment_id=b.appointment_id
                        WHERE b.session_id=s.session_id AND a.status='DEMO_CREATED') AS active_count,
                       (SELECT COUNT(*) FROM demo_appointments a
                        WHERE a.hospital_id=s.hospital_id AND a.department=s.department
                          AND a.visit_date=s.visit_date AND a.status='DEMO_CREATED') AS daily_active_count
                FROM demo_appointment_sessions s
                JOIN demo_appointment_doctors d
                  ON d.hospital_id=s.hospital_id AND d.department=s.department
                 AND d.doctor_id=s.doctor_id
                JOIN demo_appointment_time_slots t
                  ON t.hospital_id=s.hospital_id AND t.department=s.department
                 AND t.slot_id=s.slot_id
                LEFT JOIN demo_appointment_schedules ds
                  ON ds.hospital_id=s.hospital_id AND ds.department=s.department
                 AND ds.visit_date=s.visit_date
                WHERE s.hospital_id=? AND s.department=?
                  AND s.visit_date BETWEEN ? AND ?
                  AND d.enabled=1 AND t.enabled=1
                ORDER BY s.visit_date, d.sort_order, s.doctor_id, t.sort_order, s.slot_id
                """, (rs, rowNum) -> {
                    LocalDate date = rs.getDate("visit_date").toLocalDate();
                    int capacity = rs.getInt("total_capacity");
                    long active = rs.getLong("active_count");
                    boolean dailyConfigured = rs.getObject("daily_capacity") != null;
                    long sessionRemaining = Math.max(0L, capacity - active);
                    long dailyRemaining = dailyConfigured
                            ? Math.max(0L, rs.getInt("daily_capacity") - rs.getLong("daily_active_count")) : 0;
                    long remaining = Math.min(sessionRemaining, dailyRemaining);
                    boolean released = AppointmentBookingPolicy.isReleased(date, now);
                    String bookingStatus = !dailyConfigured ? "NO_SCHEDULE"
                            : !released ? "NOT_RELEASED" : remaining == 0 ? "FULL" : "AVAILABLE";
                    return new SessionItem(
                            rs.getString("session_id"), date,
                            rs.getString("doctor_id"), rs.getString("doctor_name"),
                            rs.getString("slot_id"), rs.getString("slot_name"),
                            rs.getObject("start_time", LocalTime.class).format(TIME),
                            rs.getObject("end_time", LocalTime.class).format(TIME), capacity,
                            AppointmentBookingPolicy.releaseAt(date).toOffsetDateTime().toString(),
                            released ? "RELEASED" : "NOT_RELEASED", active,
                            sessionRemaining, dailyRemaining, remaining,
                            bookingStatus, "AVAILABLE".equals(bookingStatus));
                }, id, dept, Date.valueOf(today.plusDays(1)),
                Date.valueOf(today.plusDays(AppointmentBookingPolicy.ADVANCE_DAYS)));

        return new AppointmentSessionResponse(
                items.isEmpty() ? "NO_DATA" : "DEMO_DATA",
                id, dept, today, AppointmentBookingPolicy.ZONE.getId(), true,
                List.copyOf(items),
                "仅为本地虚构演示排班。参考余量取场次余量与当天总余量的较小值。"
                        + "旧预约未指定医生时段，但仍占用当天总名额。草稿不占号，确认时再次校验并占号。"
                        + "查询不预留名额；未放号不能确认；取消后按有效预约记录自动重新计算余量。");
    }

    private String requireText(String value, String field) {
        String result = value == null ? "" : value.strip();
        if (result.isEmpty() || result.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " 不能为空，且不能超过64个字符");
        }
        return result;
    }

    public record MaintenanceResult(
            LocalDate businessDate, LocalDate fromDate, LocalDate toDate,
            int configuredSessions
    ) {}
}