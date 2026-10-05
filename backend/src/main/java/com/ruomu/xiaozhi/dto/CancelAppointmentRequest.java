package com.ruomu.xiaozhi.dto;

// 必须明确发送 {"confirmed": true}，才允许执行取消预约。
public record CancelAppointmentRequest(Boolean confirmed) {
}