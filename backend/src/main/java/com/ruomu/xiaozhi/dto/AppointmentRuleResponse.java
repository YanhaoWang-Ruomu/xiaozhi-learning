package com.ruomu.xiaozhi.dto;

public record AppointmentRuleResponse(
        String status,
        String hospitalId,
        String hospitalName,
        Integer advanceDays,
        String releaseTime,
        String timeZone,
        String source,
        String message
) {
}