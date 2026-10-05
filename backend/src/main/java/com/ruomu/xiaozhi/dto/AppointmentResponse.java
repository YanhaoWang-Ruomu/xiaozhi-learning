package com.ruomu.xiaozhi.dto;

import java.time.LocalDate;

public record AppointmentResponse(
        String appointmentId, String status, String hospitalId, String department,
        LocalDate visitDate, String timeZone, String message, String cancelledAt,
        AppointmentSessionSelection session
) {
    public AppointmentResponse(String appointmentId, String status, String hospitalId,
                               String department, LocalDate visitDate, String timeZone, String message,
                               String cancelledAt) {
        this(appointmentId, status, hospitalId, department, visitDate, timeZone,
                message, cancelledAt, null);
    }

    public AppointmentResponse(String appointmentId, String status, String hospitalId,
                               String department, LocalDate visitDate, String timeZone, String message) {
        this(appointmentId, status, hospitalId, department, visitDate, timeZone,
                message, null, null);
    }
}