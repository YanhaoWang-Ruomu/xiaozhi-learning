package com.ruomu.xiaozhi.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.*;
import java.util.function.Supplier;

/** Content-free spans. Context is explicitly restored around every async model callback. */
public final class AiTelemetry {
    private static volatile OpenTelemetry telemetry = OpenTelemetry.noop();
    public static void install(OpenTelemetry value) { telemetry = value; }
    public static Operation start(String name) {
        return new Operation(telemetry.getTracer("xiaozhi-ai", "0.2.0").spanBuilder(name).startSpan());
    }
    public static <T> T call(String name, Supplier<T> action) {
        try (var op = start(name); var scope = op.scope()) {
            try { return action.get(); } catch (RuntimeException e) { op.fail(e); throw e; }
        }
    }
    public static final class Operation implements AutoCloseable {
        public final Span span;
        public final Context context;
        private final java.util.concurrent.atomic.AtomicBoolean ended = new java.util.concurrent.atomic.AtomicBoolean();
        Operation(Span span) { this.span = span; this.context = Context.current().with(span); }
        public Scope scope() { return context.makeCurrent(); }
        public void fail(Throwable error) { span.setStatus(StatusCode.ERROR); span.setAttribute("error.type", error.getClass().getSimpleName()); }
        public void close() { if (ended.compareAndSet(false,true)) span.end(); }
    }
}
