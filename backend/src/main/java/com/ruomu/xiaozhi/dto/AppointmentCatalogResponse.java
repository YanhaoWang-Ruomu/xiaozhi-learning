package com.ruomu.xiaozhi.dto;

import java.util.List;

public record AppointmentCatalogResponse(
        String status,
        String hospitalId,
        String department,
        String timeZone,
        List<DoctorItem> doctors,
        List<TimeSlotItem> timeSlots,
        String message
) {

    public record DoctorItem(
            String doctorId,
            String doctorName
    ) {
    }

    public record TimeSlotItem(
            String slotId,
            String slotName,
            String startTime,
            String endTime
    ) {
    }
}