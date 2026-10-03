package com.ruomu.xiaozhi.dto;

import java.time.LocalDate;

public record CreateAppointmentRequest(
        String hospitalId,
        String department,
        LocalDate visitDate
) {
}