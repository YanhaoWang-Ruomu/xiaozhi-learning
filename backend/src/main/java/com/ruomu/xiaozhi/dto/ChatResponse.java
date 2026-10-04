package com.ruomu.xiaozhi.dto;

import java.util.List;

public record ChatResponse(
        String reply,
        List<AppointmentDraftResponse> drafts
) {
}