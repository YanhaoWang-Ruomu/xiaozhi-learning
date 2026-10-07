package com.ruomu.xiaozhi.observability;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.*;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import java.util.Collection;
import java.net.URI;
import org.slf4j.LoggerFactory;

@Configuration
public class TelemetryConfig {
    @Bean(destroyMethod="close")
    public OpenTelemetrySdk aiTelemetry(
            @Value("${xiaozhi.otel.endpoint:}") String endpoint,
            @Value("${xiaozhi.otel.authorization:}") String authorization) {
        validateEndpoint(endpoint, authorization);
        var provider=SdkTracerProvider.builder().setResource(Resource.create(Attributes.of(
            AttributeKey.stringKey("service.name"), "xiaozhi-learning")));
        provider.addSpanProcessor(SimpleSpanProcessor.create(new SpanExporter() {
            public CompletableResultCode export(Collection<SpanData> spans) {
                for(var s:spans) LoggerFactory.getLogger("AI_TRACE").info(
                    "traceId={} spanId={} parentSpanId={} operation={} status={} durationMs={} attributes={}",
                    s.getTraceId(),s.getSpanId(),s.getParentSpanId(),s.getName(),s.getStatus().getStatusCode(),
                    (s.getEndEpochNanos()-s.getStartEpochNanos())/1_000_000,s.getAttributes());
                return CompletableResultCode.ofSuccess();
            }
            public CompletableResultCode flush(){return CompletableResultCode.ofSuccess();}
            public CompletableResultCode shutdown(){return CompletableResultCode.ofSuccess();}
        }));
        if(!endpoint.isBlank()) {
            var exporter = OtlpHttpSpanExporter.builder()
                .setEndpoint(endpoint).setTimeout(java.time.Duration.ofSeconds(5));
            if (!authorization.isBlank()) exporter.addHeader("Authorization", authorization);
            provider.addSpanProcessor(BatchSpanProcessor.builder(exporter.build()).build());
        }
        var sdk=OpenTelemetrySdk.builder().setTracerProvider(provider.build()).build();
        AiTelemetry.install(sdk);
        return sdk;
    }
    static void validateEndpoint(String endpoint, String authorization) {
        if (authorization.contains("\r") || authorization.contains("\n"))
            throw new IllegalArgumentException("OTLP authorization must be a single header value");
        if (endpoint.isBlank()) {
            if (!authorization.isBlank()) throw new IllegalArgumentException("OTLP authorization requires an endpoint");
            return;
        }
        URI uri;
        try { uri = URI.create(endpoint); }
        catch (IllegalArgumentException invalid) {
            // Do not attach URI parser exceptions: they may include embedded credentials.
            throw new IllegalArgumentException("Invalid OTLP endpoint");
        }
        if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null ||
            uri.getHost() == null || !("https".equals(uri.getScheme()) ||
             ("http".equals(uri.getScheme()) && java.util.Set.of("127.0.0.1", "localhost").contains(uri.getHost()))))
            throw new IllegalArgumentException("OTLP requires HTTPS or local HTTP without credentials in URL");
    }
}
