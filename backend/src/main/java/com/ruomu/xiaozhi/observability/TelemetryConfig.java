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
    public OpenTelemetrySdk aiTelemetry(@Value("${xiaozhi.otel.endpoint:}") String endpoint) {
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
            URI uri=URI.create(endpoint);
            if(uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getHost()==null ||
                !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) &&
                 java.util.Set.of("127.0.0.1","localhost").contains(uri.getHost()))))
                throw new IllegalArgumentException("OTLP requires HTTPS or local HTTP without credentials in URL");
            provider.addSpanProcessor(BatchSpanProcessor.builder(OtlpHttpSpanExporter.builder()
                .setEndpoint(endpoint).setTimeout(java.time.Duration.ofSeconds(5)).build()).build());
        }
        var sdk=OpenTelemetrySdk.builder().setTracerProvider(provider.build()).build();
        AiTelemetry.install(sdk);
        return sdk;
    }
}
