package com.ruomu.xiaozhi.dto;

import java.time.LocalDate;
import java.util.List;

public record AppointmentSessionResponse(
        String status,
        String hospitalId,
        String department,
        LocalDate businessDate,
        String timeZone,
        boolean bookingEnabled,
        List<SessionItem> sessions,
        String message
) {
    public record SessionItem(
            String sessionId,
            LocalDate visitDate,
            String doctorId,
            String doctorName,
            String slotId,
            String slotName,
            String startTime,
            String endTime,
            int totalCapacity,
            String releaseAt,
            String releaseStatus
    ) {}
}
