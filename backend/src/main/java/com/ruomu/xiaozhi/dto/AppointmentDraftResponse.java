package com.ruomu.xiaozhi.dto;

import java.time.LocalDate;

public record AppointmentDraftResponse(
        String draftId, String status, String hospitalId, String department,
        LocalDate visitDate, String timeZone, String appointmentId, String message,
        AppointmentSessionSelection session
) {
    public AppointmentDraftResponse(String draftId, String status, String hospitalId,
                                    String department, LocalDate visitDate, String timeZone,
                                    String appointmentId, String message) {
        this(draftId, status, hospitalId, department, visitDate, timeZone,
                appointmentId, message, null);
    }
}