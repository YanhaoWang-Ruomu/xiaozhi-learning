package com.ruomu.xiaozhi.dto;

import java.time.LocalDate;

public record CreateAppointmentRequest(
        String hospitalId, String department, LocalDate visitDate, String sessionId
) {
    // 兼容已有工具、网页以及历史草稿的按日期预约。
    public CreateAppointmentRequest(String hospitalId, String department, LocalDate visitDate) {
        this(hospitalId, department, visitDate, null);
    }
}