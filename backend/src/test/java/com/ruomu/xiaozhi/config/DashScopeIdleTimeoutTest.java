package com.ruomu.xiaozhi.config;

import com.alibaba.dashscope.aigc.generation.*;
import com.alibaba.dashscope.common.*;
import com.alibaba.dashscope.protocol.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DashScopeIdleTimeoutTest {
    @Test void realSdkTerminatesAnIdleHttpStream() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var releaseServer = new CountDownLatch(1);
        var token = new CountDownLatch(1);
        var ended = new CountDownLatch(1);
        var failure = new AtomicReference<Exception>();
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try {
                String event = "data: {\"output\":{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"partial\"},\"finish_reason\":\"null\"}]}}\n\n";
                exchange.getResponseBody().write(event.getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                releaseServer.await(8, TimeUnit.SECONDS);
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            var options = ConnectionOptions.builder().connectTimeout(Duration.ofSeconds(1))
                .writeTimeout(Duration.ofSeconds(1)).readTimeout(Duration.ofSeconds(1)).build();
            var generation = new Generation(Protocol.HTTP.getValue(),
                "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1", options);
            var parameters = GenerationParam.builder().apiKey("test-only-not-a-real-key")
                .model("qwen-plus").resultFormat("message").incrementalOutput(true)
                .messages(List.of(Message.builder().role("user").content("test").build())).build();
            generation.streamCall(parameters, new ResultCallback<GenerationResult>() {
                @Override public void onEvent(GenerationResult value) { token.countDown(); }
                @Override public void onComplete() { ended.countDown(); }
                @Override public void onError(Exception error) { failure.set(error); ended.countDown(); }
            });
            assertThat(token.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(ended.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(failure.get()).isNotNull();
            // The SDK has already received HTTP 200; an idle body is a response_error.
            assertThat(ModelErrorSummary.from(failure.get()).serviceCode()).isEqualTo("response_error");
            assertThat(ModelErrorSummary.from(failure.get()).httpStatus()).isEqualTo("200");
        } finally { releaseServer.countDown(); server.stop(0); }
    }
}
