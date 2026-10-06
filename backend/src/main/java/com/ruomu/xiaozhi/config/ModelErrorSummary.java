package com.ruomu.xiaozhi.config;

import com.alibaba.dashscope.exception.ApiException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.regex.Pattern;

/** Never include exception messages, response bodies, prompts, or credentials. */
record ModelErrorSummary(String httpStatus, String serviceCode, String requestId, String causeType) {
    private static final Set<String> CODES = Set.of(
        "network error", "response_error", "request_cancelled", "api_key_error",
        "InvalidApiKey", "AccessDenied", "InvalidParameter", "InvalidParameter.Range",
        "DataInspectionFailed", "Throttling", "Throttling.RateQuota",
        "Throttling.AllocationQuota", "Throttling.RateLimit", "ModelNotFound",
        "InternalError", "InternalError.Algo", "SystemError", "ServiceUnavailable", "RequestTimeOut");
    private static final Pattern ID = Pattern.compile(
        "(?:[0-9a-fA-F]{32}|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})");

    static ModelErrorSummary from(Throwable error) {
        String status = "UNKNOWN", code = "UNKNOWN", request = "UNKNOWN", cause = "UNKNOWN";
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        boolean apiFound = false;
        for (Throwable current = error; current != null && seen.size() < 16 && seen.add(current);
                current = current.getCause()) {
            cause = current.getClass().getSimpleName();
            if (!apiFound && current instanceof ApiException api && api.getStatus() != null) {
                apiFound = true;
                var detail = api.getStatus();
                int number = detail.getStatusCode();
                status = number == -1 || number >= 100 && number <= 599 ? String.valueOf(number) : "UNKNOWN";
                String value = detail.getCode();
                code = value == null ? "UNKNOWN" : CODES.contains(value) ? value.replace(' ', '_') : "OTHER";
                value = detail.getRequestId();
                request = value != null && ID.matcher(value).matches() ? value : "UNKNOWN";
            }
        }
        return new ModelErrorSummary(status, code, request, cause);
    }
}
