package com.ruomu.examples.flux;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.reactor.TokenStreamToFluxAdapter;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

class FluxComparisonTest {
    @SuppressWarnings("unchecked")
    private Flux<String> nativeFlux(ControlledTokenStream stream) {
        return (Flux<String>) new TokenStreamToFluxAdapter().adapt(stream);
    }
    private static ChatResponse answer(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }
    private static List<Content> sources() {
        return List.of(Content.from(TextSegment.from("演示预约资料",
                new Metadata().put("source", "knowledge/demo.txt").put("index", 0))));
    }

    @Test void nativeAdapterStartsBeforeSubscriptionAndEmitsOnlyPartialText() {
        var provider = new ControlledTokenStream();
        var flux = nativeFlux(provider);
        assertThat(provider.starts).isEqualTo(1); // beta3 is eager, not defer-until-subscribe.
        provider.retrieved.accept(sources());
        provider.partial.accept("你好");
        provider.completed.accept(answer("你好，最终完整回复"));
        StepVerifier.create(flux).expectNext("你好").verifyComplete();
        // Sources and the final full response are not elements of Flux<String>.
    }

    @Test void nativeAdapterPropagatesProviderError() {
        var provider = new ControlledTokenStream();
        StepVerifier.create(nativeFlux(provider))
                .then(() -> provider.error.accept(new IllegalStateException("provider failed")))
                .expectErrorMessage("provider failed").verify();
    }

    @Test void nativeAdapterIsUnicastAndCancellationDoesNotUndoStart() {
        var provider = new ControlledTokenStream();
        var flux = nativeFlux(provider);
        StepVerifier.create(flux).thenCancel().verify();
        assertThat(provider.starts).isEqualTo(1);
        // TokenStream in this version has no cancel method. Late callbacks still run.
        provider.partial.accept("late token");
        provider.completed.accept(answer("late answer"));
        StepVerifier.create(flux).expectError(IllegalStateException.class).verify();
    }

    @Test void eventBridgePreservesSourcesAndSavesBeforeDoneExactlyOnce() {
        var provider = new ControlledTokenStream();
        var saved = new ArrayList<EventFluxBridge.Result>();
        var releases = new AtomicInteger();
        var flux = EventFluxBridge.open(provider, saved::add, releases::incrementAndGet);
        assertThat(provider.starts).isZero();
        StepVerifier.create(flux)
                .assertNext(e -> assertThat(e.event()).isEqualTo("status"))
                .then(() -> provider.retrieved.accept(sources()))
                .assertNext(e -> assertThat(e.event()).isEqualTo("sources"))
                .then(() -> provider.partial.accept("你好"))
                .assertNext(e -> assertThat(e.event()).isEqualTo("token"))
                .then(() -> provider.tool.accept(null)) // Callback only, no actual tool executed.
                .assertNext(e -> assertThat(e.event()).isEqualTo("status"))
                .then(() -> provider.completed.accept(answer("你好，完整回复")))
                .assertNext(e -> {
                    assertThat(e.event()).isEqualTo("done");
                    assertThat(saved).hasSize(1);
                    assertThat(saved.get(0).reply()).isEqualTo("你好，完整回复");
                    assertThat(saved.get(0).drafts()).isEmpty();
                    assertThat(saved.get(0).sources().get(0)).containsEntry("index", 0);
                }).verifyComplete();
        provider.completed.accept(answer("duplicate"));
        provider.error.accept(new IllegalStateException("late error"));
        assertThat(saved).hasSize(1);
        assertThat(releases).hasValue(1);
        assertThat(provider.starts).isEqualTo(1);
    }

    @Test void providerErrorProducesFailedEventWithoutSavingSuccess() {
        var provider = new ControlledTokenStream();
        var saved = new AtomicInteger();
        var releases = new AtomicInteger();
        StepVerifier.create(EventFluxBridge.open(provider, value -> saved.incrementAndGet(), releases::incrementAndGet))
                .expectNextMatches(e -> "status".equals(e.event()))
                .then(() -> provider.error.accept(new RuntimeException("secret internal detail")))
                .assertNext(e -> {
                    assertThat(e.event()).isEqualTo("failed");
                    assertThat(e.data().toString()).doesNotContain("secret");
                }).verifyComplete();
        assertThat(saved).hasValue(0);
        assertThat(releases).hasValue(1);
    }

    @Test void persistenceFailureDoesNotEmitDone() {
        var provider = new ControlledTokenStream();
        var releases = new AtomicInteger();
        StepVerifier.create(EventFluxBridge.open(provider, value -> { throw new IllegalStateException("database"); },
                        releases::incrementAndGet))
                .expectNextMatches(e -> "status".equals(e.event()))
                .then(() -> provider.completed.accept(answer("answer")))
                .expectNextMatches(e -> "failed".equals(e.event())).verifyComplete();
        assertThat(releases).hasValue(1);
    }

    @Test void lengthLimitedResponseIsNotSuccessfulCompletion() {
        var provider = new ControlledTokenStream();
        var saved = new AtomicInteger();
        StepVerifier.create(EventFluxBridge.open(provider, value -> saved.incrementAndGet(), () -> {}))
                .expectNextMatches(e -> "status".equals(e.event()))
                .then(() -> provider.completed.accept(ChatResponse.builder().aiMessage(AiMessage.from("partial"))
                        .finishReason(FinishReason.LENGTH).build()))
                .expectNextMatches(e -> "failed".equals(e.event())).verifyComplete();
        assertThat(saved).hasValue(0);
    }

    @Test void clientCancellationKeepsModelLeaseUntilRealCompletionAndStillSaves() {
        var provider = new ControlledTokenStream();
        var saved = new AtomicInteger();
        var releases = new AtomicInteger();
        StepVerifier.create(EventFluxBridge.open(provider, value -> saved.incrementAndGet(), releases::incrementAndGet))
                .expectNextMatches(e -> "status".equals(e.event())).thenCancel().verify();
        assertThat(releases).hasValue(0);
        assertThat(saved).hasValue(0);
        provider.partial.accept("still running");
        provider.completed.accept(answer("completed after disconnect"));
        assertThat(saved).hasValue(1);
        assertThat(releases).hasValue(1);
    }

    @Test void inactivityTimeoutStopsDeliveryButDoesNotReleaseRunningModel() {
        var provider = new ControlledTokenStream();
        var releases = new AtomicInteger();
        var saved = new AtomicInteger();
        StepVerifier.withVirtualTime(() -> EventFluxBridge.open(provider, value -> saved.incrementAndGet(), releases::incrementAndGet)
                        .timeout(Duration.ofSeconds(5))
                        .onErrorResume(TimeoutException.class, error -> Flux.just(EventFluxBridge.event("failed", "timeout"))))
                .expectNextMatches(e -> "status".equals(e.event()))
                .thenAwait(Duration.ofSeconds(5))
                .expectNextMatches(e -> "failed".equals(e.event())).verifyComplete();
        assertThat(releases).hasValue(0);
        provider.completed.accept(answer("late"));
        assertThat(saved).hasValue(1);
        assertThat(releases).hasValue(1);
    }

    @Test void fluxTimeoutMeasuresGapBetweenElementsNotTotalDuration() {
        StepVerifier.withVirtualTime(() -> Flux.interval(Duration.ofSeconds(4)).take(3).timeout(Duration.ofSeconds(5)))
                .thenAwait(Duration.ofSeconds(12)).expectNext(0L, 1L, 2L).verifyComplete();
    }

    @Test void secondSubscriptionCannotRestartOneModelTurn() {
        var provider = new ControlledTokenStream();
        var flux = EventFluxBridge.open(provider, value -> {}, () -> {});
        StepVerifier.create(flux).expectNextCount(1).thenCancel().verify();
        StepVerifier.create(flux).expectErrorMessage("A model turn accepts only one subscription").verify();
        assertThat(provider.starts).isEqualTo(1);
        provider.completed.accept(answer("end"));
    }
}
