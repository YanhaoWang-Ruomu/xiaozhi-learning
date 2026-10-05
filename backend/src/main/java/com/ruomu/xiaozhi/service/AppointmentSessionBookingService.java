package com.ruomu.xiaozhi.service;

import com.ruomu.xiaozhi.dto.AppointmentSessionSelection;
import com.ruomu.xiaozhi.dto.CreateAppointmentRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

// 由AppointmentService持有；写入方法必须在调用方MySQL事务内执行。
final class AppointmentSessionBookingService {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private final JdbcTemplate jdbc;
    private static final String SELECTION_SQL = """
            SELECT s.session_id, s.hospital_id, s.department, s.visit_date,
                   s.total_capacity, s.doctor_id, d.doctor_name, d.enabled AS doctor_enabled,
                   s.slot_id, t.slot_name, t.start_time, t.end_time, t.enabled AS slot_enabled
            FROM demo_appointment_sessions s
            JOIN demo_appointment_doctors d
              ON d.hospital_id=s.hospital_id AND d.department=s.department
             AND d.doctor_id=s.doctor_id
            JOIN demo_appointment_time_slots t
              ON t.hospital_id=s.hospital_id AND t.department=s.department
             AND t.slot_id=s.slot_id
            WHERE s.session_id=?
            """;

    AppointmentSessionBookingService(DataSource source) {
        jdbc = new JdbcTemplate(source);
        jdbc.queryForObject("SELECT COUNT(*) FROM demo_appointment_request_targets WHERE 1=0", Long.class);
        jdbc.queryForObject("SELECT COUNT(*) FROM demo_appointment_session_bookings WHERE 1=0", Long.class);
    }

    String normalizeId(String raw) {
        if (raw == null) return null;
        String id = raw.strip();
        if (!id.matches("[1-9][0-9]{0,18}")) throw badRequest("sessionId必须是有效的正整数编号");
        try { Long.parseLong(id); }
        catch (NumberFormatException e) { throw badRequest("sessionId超出范围"); }
        return id;
    }

    AppointmentSessionSelection forDraft(CreateAppointmentRequest request) {
        String id = normalizeId(request.sessionId());
        if (id == null) return null;
        Row row = read(id, false);
        String invalid = invalid(row, request);
        if (invalid != null) throw badRequest(invalid);
        return row.selection();
    }

    // 调用方已锁定同一个request_id，因此首次绑定与重试校验不会竞争。
    void bindTarget(String requestId, String sessionId) {
        var targets = jdbc.query("SELECT session_id FROM demo_appointment_request_targets WHERE request_id=?",
                (rs, n) -> rs.getString("session_id"), requestId);
        if (targets.isEmpty()) {
            jdbc.update("INSERT INTO demo_appointment_request_targets(request_id,session_id) VALUES (?,?)",
                    requestId, sessionId == null ? null : Long.valueOf(sessionId));
        } else if (!Objects.equals(targets.get(0), sessionId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "同一个 Idempotency-Key 不能用于不同的预约场次");
        }
    }

    Decision lockAndCheck(CreateAppointmentRequest request, AppointmentSessionSelection expected) {
        String id = normalizeId(request.sessionId());
        if (id == null) return new Decision(null, null);
        Row row = read(id, true);
        String invalid = invalid(row, request);
        if (invalid != null) return new Decision(null, invalid + "；请新建草稿。");
        if (expected != null && !expected.equals(row.selection())) {
            return new Decision(null, "医生或时段信息已发生变化；请重新核对并新建草稿。");
        }
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM demo_appointment_session_bookings b
                JOIN demo_appointments a ON a.appointment_id=b.appointment_id
                WHERE b.session_id=? AND a.status='DEMO_CREATED'
                """, Long.class, Long.valueOf(id));
        if (Objects.requireNonNull(count) >= row.capacity()) {
            return new Decision(null, "该医生时段名额已满；本次确认已拒绝，请重新选择场次并新建草稿。");
        }
        return new Decision(row.selection(), null);
    }

    void save(String appointmentId, AppointmentSessionSelection s) {
        if (s == null) return;
        jdbc.update("""
                INSERT INTO demo_appointment_session_bookings
                    (appointment_id,session_id,doctor_id,doctor_name,
                     slot_id,slot_name,start_time,end_time)
                VALUES (?,?,?,?,?,?,?,?)
                """, appointmentId, Long.valueOf(s.sessionId()), s.doctorId(), s.doctorName(),
                s.slotId(), s.slotName(), java.sql.Time.valueOf(s.startTime()),
                java.sql.Time.valueOf(s.endTime()));
    }

    AppointmentSessionSelection find(String appointmentId) {
        var list = jdbc.query("""
                SELECT session_id,doctor_id,doctor_name,slot_id,slot_name,start_time,end_time
                FROM demo_appointment_session_bookings WHERE appointment_id=?
                """, (rs, n) -> selection(rs), appointmentId);
        return list.isEmpty() ? null : list.get(0);
    }

    private Row read(String id, boolean lock) {
        var rows = jdbc.query(SELECTION_SQL + (lock ? " FOR UPDATE" : ""),
                (rs, n) -> new Row(rs.getString("hospital_id"), rs.getString("department"),
                        rs.getDate("visit_date").toLocalDate(), rs.getInt("total_capacity"),
                        rs.getInt("doctor_enabled") == 1 && rs.getInt("slot_enabled") == 1,
                        selection(rs)), Long.valueOf(id));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String invalid(Row row, CreateAppointmentRequest request) {
        if (row == null) return "未找到该演示场次";
        if (!row.hospital().equals(request.hospitalId().strip())
                || !row.department().equals(request.department().strip())
                || !row.date().equals(request.visitDate())) {
            return "场次与医院、科室或就诊日期不一致";
        }
        if (!row.enabled()) return "该场次的医生或时段已停用";
        return null;
    }

    private AppointmentSessionSelection selection(ResultSet rs) throws SQLException {
        return new AppointmentSessionSelection(rs.getString("session_id"),
                rs.getString("doctor_id"), rs.getString("doctor_name"),
                rs.getString("slot_id"), rs.getString("slot_name"),
                rs.getObject("start_time", LocalTime.class).format(TIME),
                rs.getObject("end_time", LocalTime.class).format(TIME));
    }

    private ResponseStatusException badRequest(String text) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, text);
    }

    record Decision(AppointmentSessionSelection selection, String rejection) {}
    private record Row(String hospital, String department, LocalDate date,
                       int capacity, boolean enabled, AppointmentSessionSelection selection) {}
}