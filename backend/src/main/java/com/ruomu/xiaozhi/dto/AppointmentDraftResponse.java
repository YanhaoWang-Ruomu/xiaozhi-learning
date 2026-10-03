package com.ruomu.xiaozhi.dto;

import java.time.LocalDate;

public record AppointmentDraftResponse(
        String draftId,
        String status,
        String hospitalId,
        String department,
        LocalDate visitDate,
        String timeZone,
        String appointmentId,
        String message
) {
}