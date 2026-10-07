package com.ruomu.xiaozhi.config;

import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class TokenUsageListener implements ChatModelListener {

    private static final Logger log =
            LoggerFactory.getLogger(TokenUsageListener.class);

    private final String mode;
    private final String modelName;

    private final Object callIdKey = new Object();
    private final Object startedAtKey = new Object();

    public TokenUsageListener(String mode, String modelName) {
        this.mode = mode;
        this.modelName = modelName;
    }

    @Override
    public void onRequest(ChatModelRequestContext context) {
        context.attributes().put("xiaozhi.traceId", io.opentelemetry.api.trace.Span.current().getSpanContext().getTraceId());
        // attributes 属于本次模型调用。
        // 不把请求计时放在共享字段中，避免并发调用互相覆盖。
        context.attributes().put(
                callIdKey,
                UUID.randomUUID().toString()
        );

        context.attributes().put(
                startedAtKey,
                System.nanoTime()
        );
    }

    @Override
    public void onResponse(ChatModelResponseContext context) {
        TokenUsage usage = context.chatResponse().tokenUsage();

        log.info(
                "TOKEN_USAGE traceId={} callId={} mode={} model={} outcome=RESPONSE "
                        + "inputTokens={} outputTokens={} totalTokens={} "
                        + "durationMs={} finishReason={}",
                context.attributes().getOrDefault("xiaozhi.traceId", "UNKNOWN"),
                callId(context.attributes()),
                mode,
                modelName,
                count(usage == null ? null : usage.inputTokenCount()),
                count(usage == null ? null : usage.outputTokenCount()),
                count(usage == null ? null : usage.totalTokenCount()),
                durationMs(context.attributes()),
                context.chatResponse().finishReason()
        );
    }

    @Override
    public void onError(ChatModelErrorContext context) {
        // 失败不代表零消耗；没有最终用量时明确记为 UNKNOWN。
        // 仅记录经过限制的状态信息，不输出异常消息、响应正文或凭据。
        ModelErrorSummary detail = ModelErrorSummary.from(context.error());
        log.warn(
                "TOKEN_USAGE traceId={} callId={} mode={} model={} outcome=ERROR "
                        + "inputTokens=UNKNOWN outputTokens=UNKNOWN "
                        + "totalTokens=UNKNOWN durationMs={} errorType={} "
                        + "httpStatus={} serviceCode={} requestId={} causeType={}",
                context.attributes().getOrDefault("xiaozhi.traceId", "UNKNOWN"),
                callId(context.attributes()),
                mode,
                modelName,
                durationMs(context.attributes()),
                context.error().getClass().getSimpleName(),
                detail.httpStatus(), detail.serviceCode(), detail.requestId(), detail.causeType()
        );
    }

    private String callId(Map<Object, Object> attributes) {
        Object value = attributes.get(callIdKey);
        return value instanceof String id ? id : "UNKNOWN";
    }

    private String durationMs(Map<Object, Object> attributes) {
        Object value = attributes.get(startedAtKey);

        if (!(value instanceof Long startedAt)) {
            return "UNKNOWN";
        }

        return Long.toString(
                TimeUnit.NANOSECONDS.toMillis(
                        System.nanoTime() - startedAt
                )
        );
    }

    private String count(Integer value) {
        return value == null ? "UNKNOWN" : value.toString();
    }
}
