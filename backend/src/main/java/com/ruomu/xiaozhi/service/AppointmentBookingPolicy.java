package com.ruomu.xiaozhi.service;

import com.ruomu.xiaozhi.dto.CreateAppointmentRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public final class AppointmentBookingPolicy {

    public static final String HOSPITAL_ID = "DEMO001";
    public static final String DEPARTMENT = "内科";
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public static final int ADVANCE_DAYS = 3;
    public static final LocalTime RELEASE_TIME = LocalTime.of(9, 30);

    private AppointmentBookingPolicy() {
    }

    public static LocalDate businessDate(Instant now) {
        return now.atZone(ZONE).toLocalDate();
    }

    public static ZonedDateTime releaseAt(LocalDate visitDate) {
        return visitDate.minusDays(ADVANCE_DAYS)
                .atTime(RELEASE_TIME)
                .atZone(ZONE);
    }

    public static boolean isReleased(LocalDate visitDate, Instant now) {
        // 正好09:30:00即开放。
        return !now.isBefore(releaseAt(visitDate).toInstant());
    }

    public static void validateSelection(
            CreateAppointmentRequest request,
            Instant now) {

        if (request == null) {
            throw badRequest("预约请求不能为空");
        }

        if (!HOSPITAL_ID.equals(normalize(request.hospitalId()))) {
            throw badRequest("当前仅支持演示医院 " + HOSPITAL_ID);
        }

        if (!DEPARTMENT.equals(normalize(request.department()))) {
            throw badRequest("当前仅支持演示科室：" + DEPARTMENT);
        }

        LocalDate today = businessDate(now);
        LocalDate date = request.visitDate();

        if (date == null
                || !date.isAfter(today)
                || date.isAfter(today.plusDays(ADVANCE_DAYS))) {

            throw badRequest(
                    "预约日期必须为上海时区明天起的未来"
                            + ADVANCE_DAYS + "天内"
            );
        }
    }

    public static void validateConfirmation(
            CreateAppointmentRequest request,
            Instant now) {

        validateSelection(request, now);

        if (!isReleased(request.visitDate(), now)) {
            String release = releaseAt(request.visitDate()).format(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            );

            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "该日期演示排班尚未放号，开放时间为上海时间"
                            + release
                            + "；草稿保留待确认，请到时再点击确认。"
            );
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                message
        );
    }
}