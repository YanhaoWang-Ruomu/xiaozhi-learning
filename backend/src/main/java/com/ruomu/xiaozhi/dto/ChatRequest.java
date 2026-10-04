package com.ruomu.xiaozhi.dto;

public record ChatRequest(
        String conversationId,
        String message
) {
}