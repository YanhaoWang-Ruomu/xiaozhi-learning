package com.ruomu.xiaozhi.service;

import com.ruomu.xiaozhi.dto.AppointmentCatalogResponse;
import com.ruomu.xiaozhi.dto.AppointmentCatalogResponse.DoctorItem;
import com.ruomu.xiaozhi.dto.AppointmentCatalogResponse.TimeSlotItem;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class AppointmentCatalogService {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    private final JdbcTemplate jdbc;

    public AppointmentCatalogService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public AppointmentCatalogResponse findCatalog(
            String hospitalId,
            String department) {

        String id = requireText(hospitalId, "hospitalId");
        String dept = requireText(department, "department");

        List<DoctorItem> doctors = jdbc.query("""
                SELECT doctor_id, doctor_name
                FROM demo_appointment_doctors
                WHERE hospital_id = ?
                  AND department = ?
                  AND enabled = 1
                ORDER BY sort_order, doctor_id
                """,
                (rs, rowNum) -> new DoctorItem(
                        rs.getString("doctor_id"),
                        rs.getString("doctor_name")
                ),
                id,
                dept
        );

        List<TimeSlotItem> slots = jdbc.query("""
                SELECT slot_id, slot_name, start_time, end_time
                FROM demo_appointment_time_slots
                WHERE hospital_id = ?
                  AND department = ?
                  AND enabled = 1
                ORDER BY sort_order, slot_id
                """,
                (rs, rowNum) -> new TimeSlotItem(
                        rs.getString("slot_id"),
                        rs.getString("slot_name"),
                        rs.getObject("start_time", LocalTime.class)
                                .format(TIME_FORMAT),
                        rs.getObject("end_time", LocalTime.class)
                                .format(TIME_FORMAT)
                ),
                id,
                dept
        );

        boolean complete =
                !doctors.isEmpty() && !slots.isEmpty();

        String message = complete
                ? "仅为本地虚构演示目录；医生与时段列表不代表具体日期的排班或可用号源。"
                + "本接口不创建草稿、不办理预约、不预留名额。"
                : "该医院、科室的启用医生或时段目录尚未配置完整。"
                + "这不代表真实医院没有医生或号源。";

        return new AppointmentCatalogResponse(
                complete ? "DEMO_DATA" : "NO_DATA",
                id,
                dept,
                AppointmentBookingPolicy.ZONE.getId(),
                List.copyOf(doctors),
                List.copyOf(slots),
                message
        );
    }

    private String requireText(String value, String field) {
        String normalized =
                value == null ? "" : value.strip();

        if (normalized.isEmpty() || normalized.length() > 64) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    field + " 不能为空，且不能超过64个字符"
            );
        }

        return normalized;
    }
}