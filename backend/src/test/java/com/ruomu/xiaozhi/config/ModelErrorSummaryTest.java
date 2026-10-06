package com.ruomu.xiaozhi.config;

import com.alibaba.dashscope.common.Status;
import com.alibaba.dashscope.exception.ApiException;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ModelErrorSummaryTest {
    @Test void extractsStatusThroughWrapperWithoutMessage() {
        var detail = Status.builder().statusCode(429).code("Throttling.RateQuota")
            .requestId("12345678-1234-1234-1234-123456789abc").message("SECRET_REQUEST_BODY").build();
        var result = ModelErrorSummary.from(new RuntimeException("SECRET_WRAPPER", new ApiException(detail)));
        assertThat(result.httpStatus()).isEqualTo("429");
        assertThat(result.serviceCode()).isEqualTo("Throttling.RateQuota");
        assertThat(result.requestId()).isEqualTo(detail.getRequestId());
        assertThat(result.toString()).doesNotContain("SECRET");
    }
    @Test void reportsNetworkTimeoutWithoutLeakingMessage() {
        var result = ModelErrorSummary.from(new ApiException(new SocketTimeoutException("SECRET_URL")));
        assertThat(result.httpStatus()).isEqualTo("-1");
        assertThat(result.serviceCode()).isEqualTo("network_error");
        assertThat(result.causeType()).isEqualTo("SocketTimeoutException");
        assertThat(result.toString()).doesNotContain("SECRET");
    }
    @Test void rejectsArbitraryProviderStrings() {
        var result = ModelErrorSummary.from(new ApiException(Status.builder().statusCode(999)
            .code("sk-test-secret").requestId("sk-test-secret").message("SECRET").build()));
        assertThat(result.httpStatus()).isEqualTo("UNKNOWN");
        assertThat(result.serviceCode()).isEqualTo("OTHER");
        assertThat(result.requestId()).isEqualTo("UNKNOWN");
        assertThat(result.toString()).doesNotContain("sk-", "SECRET");
    }
    @Test void toleratesNullStatusAndError() {
        assertThat(ModelErrorSummary.from(new ApiException((Status) null)).httpStatus()).isEqualTo("UNKNOWN");
        assertThat(ModelErrorSummary.from(null).causeType()).isEqualTo("UNKNOWN");
    }
    @Test void terminatesOnCyclicCauses() {
        var one = new RuntimeException(); var two = new RuntimeException();
        one.initCause(two); two.initCause(one);
        assertThat(ModelErrorSummary.from(one).httpStatus()).isEqualTo("UNKNOWN");
    }
}
