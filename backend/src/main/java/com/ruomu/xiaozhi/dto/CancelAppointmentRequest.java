package com.ruomu.xiaozhi.dto;

// 必须显式发送 {"confirmed": true}；缺失或 false 均不执行取消。
public record CancelAppointmentRequest(Boolean confirmed) {
}
