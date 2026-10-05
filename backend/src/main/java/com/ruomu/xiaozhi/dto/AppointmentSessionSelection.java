package com.ruomu.xiaozhi.dto;

// 保存用户核对过的医生、时段快照，避免目录改名后历史预约跟着变化。
public record AppointmentSessionSelection(
        String sessionId, String doctorId, String doctorName,
        String slotId, String slotName, String startTime, String endTime
) {}