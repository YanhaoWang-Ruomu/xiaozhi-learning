package com.ruomu.xiaozhi.dto;

import java.util.List;

public record ChatResponse(
        String reply,
        List<AppointmentDraftResponse> drafts,
        List<Source> sources
) {
    // 保留原来的两参数构造方式，兼容其他已有调用。
    public ChatResponse(String reply, List<AppointmentDraftResponse> drafts) {
        this(reply, drafts, List.of());
    }

    public record Source(String source, int index, String text) {
    }
}