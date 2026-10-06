package com.ruomu.examples.flux;

import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.service.TokenStream;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Offline learning bridge, not a replacement for the main controller's security/history/tool logic. */
public final class EventFluxBridge {
    public record Result(String reply, List<Object> drafts, List<Map<String, Object>> sources) {}

    private EventFluxBridge() {}

    public static ServerSentEvent<Object> event(String name, Object data) {
        return ServerSentEvent.builder(data).event(name).build();
    }

    /** One model turn, one subscriber. save/release stand in for real persistence and the model lease. */
    public static Flux<ServerSentEvent<Object>> open(TokenStream stream,
                                                    Consumer<Result> save,
                                                    Runnable release) {
        AtomicBoolean subscribed = new AtomicBoolean();
        return Flux.create(sink -> {
            if (!subscribed.compareAndSet(false, true)) {
                sink.error(new IllegalStateException("A model turn accepts only one subscription"));
                return;
            }
            AtomicBoolean finished = new AtomicBoolean();
            AtomicReference<List<Map<String, Object>>> sources = new AtomicReference<>(List.of());
            Consumer<Throwable> fail = ignored -> {
                if (!finished.compareAndSet(false, true)) return;
                try {
                    sink.next(event("failed", Map.of("message", "本轮未完成，请核实历史后再操作。")));
                    sink.complete();
                } finally {
                    release.run();
                }
            };
            // No release in onCancel/doFinally: cancelling delivery cannot stop beta3 TokenStream.
            try {
                sink.next(event("status", Map.of("message", "正在生成回复")));
                stream.onRetrieved(contents -> {
                    List<Map<String, Object>> found = contents.stream().map(content -> {
                        var segment = content.textSegment();
                        return Map.<String, Object>of(
                                "source", segment.metadata().getString("source"),
                                "index", segment.metadata().getInteger("index"),
                                "text", segment.text());
                    }).toList();
                    sources.set(found);
                    sink.next(event("sources", Map.of("sources", found)));
                }).onPartialResponse(text -> sink.next(event("token", Map.of("text", text))))
                  .onToolExecuted(tool -> sink.next(event("status", Map.of("message", "工具执行完成"))))
                  .onCompleteResponse(response -> {
                      if (!finished.compareAndSet(false, true)) return;
                      try {
                          String reply = response.aiMessage().text();
                          if (response.finishReason() == FinishReason.LENGTH || reply == null || reply.isBlank()) {
                              throw new IllegalStateException("Incomplete response");
                          }
                          // Demo has no appointments; never invent draft IDs from generated text.
                          Result result = new Result(reply, List.of(), sources.get());
                          save.accept(result); // Also runs after downstream cancellation.
                          sink.next(event("done", result));
                          sink.complete();
                      } catch (RuntimeException e) {
                          sink.next(event("failed", Map.of("message", "回复未能完整保存，请核实历史。")));
                          sink.complete();
                      } finally {
                          release.run();
                      }
                  }).onError(fail).start();
            } catch (RuntimeException e) {
                fail.accept(e);
            }
        }, FluxSink.OverflowStrategy.ERROR);
    }
}
