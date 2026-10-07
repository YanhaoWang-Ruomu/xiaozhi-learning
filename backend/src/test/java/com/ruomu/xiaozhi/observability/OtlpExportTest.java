package com.ruomu.xiaozhi.observability;

import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class OtlpExportTest {
    @Test void exportsProtobufWithAuthorizationAndSharedTraceWithoutContent() throws Exception {
        var body = new AtomicReference<byte[]>();
        var auth = new AtomicReference<String>();
        var contentType = new AtomicReference<String>();
        var received = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/traces", exchange -> {
            body.set(exchange.getRequestBody().readAllBytes());
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            exchange.getResponseHeaders().set("Content-Type", "application/x-protobuf");
            exchange.sendResponseHeaders(200, -1);
            exchange.close(); received.countDown();
        });
        server.start();
        try (var sdk = new TelemetryConfig().aiTelemetry(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/traces", "Basic test-only")) {
            var root = AiTelemetry.start("chat.stream");
            try (var scope = root.scope()) {
                AiTelemetry.call("knowledge.retrieve", () -> "private patient question");
                AiTelemetry.call("history.save", () -> "private answer");
            }
            root.close();
            var flush = sdk.getSdkTracerProvider().forceFlush().join(10, TimeUnit.SECONDS);
            assertTrue(flush.isSuccess()); assertTrue(received.await(2, TimeUnit.SECONDS));
            assertEquals("Basic test-only", auth.get()); assertEquals("application/x-protobuf", contentType.get());
            String wire = new String(body.get(), StandardCharsets.ISO_8859_1);
            assertTrue(wire.contains("xiaozhi-learning")); assertTrue(wire.contains("chat.stream"));
            assertTrue(wire.contains("knowledge.retrieve")); assertTrue(wire.contains("history.save"));
            assertFalse(wire.contains("private patient")); assertFalse(wire.contains("private answer"));
            assertFalse(wire.contains("Basic test-only"));
            byte[] trace = HexFormat.of().parseHex(root.span.getSpanContext().getTraceId());
            assertEquals(3, occurrences(body.get(), trace));
        } finally { server.stop(0); AiTelemetry.install(OpenTelemetry.noop()); }
    }

    @Test void rejectsUnsafeEndpointsWithoutEchoingSecrets() {
        for (String endpoint : new String[]{"http://example.org/v1/traces", "https://secret@example.org/v1/traces",
                "https://example.org/v1/traces?token=secret", "https://example.org/#secret", "bad secret url"}) {
            var error = assertThrows(IllegalArgumentException.class,
                () -> new TelemetryConfig().aiTelemetry(endpoint, ""));
            assertFalse(error.toString().contains("secret")); assertNull(error.getCause());
        }
    }

    @Test void rejectsMalformedOrUnusedAuthorization() {
        assertThrows(IllegalArgumentException.class, () -> new TelemetryConfig().aiTelemetry("", "Basic test-only"));
        assertThrows(IllegalArgumentException.class, () -> new TelemetryConfig().aiTelemetry(
            "https://example.org/v1/traces", "Basic test\r\nInjected: yes"));
    }

    @Test void exporterRejectionDoesNotFailApplicationOperation() throws Exception {
        var rejected = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/traces", exchange -> {
            exchange.getRequestBody().readAllBytes(); exchange.sendResponseHeaders(401, -1); exchange.close(); rejected.countDown();
        });
        server.start();
        try (var sdk = new TelemetryConfig().aiTelemetry(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/traces", "")) {
            assertEquals("completed", AiTelemetry.call("history.save", () -> "completed"));
            // Batch flush completion does not prove backend ingestion; observe the HTTP receiver.
            sdk.getSdkTracerProvider().forceFlush().join(10, TimeUnit.SECONDS);
            assertTrue(rejected.await(2, TimeUnit.SECONDS));
            assertEquals("still working", AiTelemetry.call("history.save", () -> "still working"));
        } finally { server.stop(0); AiTelemetry.install(OpenTelemetry.noop()); }
    }

    private static int occurrences(byte[] haystack, byte[] needle) {
        int count = 0;
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            boolean match = true;
            for (int j = 0; j < needle.length; j++) if (haystack[i+j] != needle[j]) { match=false; break; }
            if (match) count++;
        }
        return count;
    }
}
