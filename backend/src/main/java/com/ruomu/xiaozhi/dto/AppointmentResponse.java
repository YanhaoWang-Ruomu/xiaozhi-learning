package com.ruomu.xiaozhi.dto;

import java.time.LocalDate;

public record AppointmentResponse(
        String appointmentId,
        String status,
        String hospitalId,
        String department,
        LocalDate visitDate,
        String timeZone,
        String message,
        String cancelledAt
) {
    // 保留七参数构造方法，兼容已有调用。
    public AppointmentResponse(String appointmentId, String status,
            String hospitalId, String department, LocalDate visitDate,
            String timeZone, String message) {
        this(appointmentId, status, hospitalId, department, visitDate,
                timeZone, message, null);
    }
}
