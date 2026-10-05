package com.ruomu.xiaozhi.dto;

import java.time.LocalDate;
import java.util.List;

public record AppointmentScheduleResponse(
        String status,
        String hospitalId,
        String department,
        LocalDate businessDate,
        String timeZone,
        boolean capacityEnforced,
        List<ScheduleItem> schedules,
        String message
) {

    public record ScheduleItem(
            LocalDate visitDate,
            int totalCapacity,
            long activeAppointmentCount,
            long referenceRemaining
    ) {
    }
}