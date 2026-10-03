package com.ruomu.xiaozhi.dto;

import java.time.LocalDate;

public record AppointmentResponse(
        String appointmentId,
        String status,
        String hospitalId,
        String department,
        LocalDate visitDate,
        String timeZone,
        String message
) {
}