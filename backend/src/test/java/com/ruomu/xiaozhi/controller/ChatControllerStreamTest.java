package com.ruomu.xiaozhi.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruomu.xiaozhi.dto.AppointmentDraftResponse;
import com.ruomu.xiaozhi.dto.ChatRequest;
import com.ruomu.xiaozhi.dto.ChatResponse;
import com.ruomu.xiaozhi.security.AccountUser;
import com.ruomu.xiaozhi.service.AppointmentDraftService;
import com.ruomu.xiaozhi.service.ChatAssistant;
import com.ruomu.xiaozhi.service.ConversationHistoryService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.service.tool.ToolExecution;
import jakarta.servlet.AsyncEvent;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/** Real main controller + Spring MVC SSE serialization. Provider/storage are controlled boundaries. */
@Timeout(15)
class ChatControllerStreamTest {
    private static final String OWNER = "11111111-1111-1111-1111-111111111111";
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final ChatAssistant assistant = mock(ChatAssistant.class);
    private final AppointmentDraftService drafts = mock(AppointmentDraftService.class);
    private final ConversationHistoryService history = mock(ConversationHistoryService.class);
    private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<ControlledChatStream> providers = new ConcurrentLinkedQueue<>();
    private final Authentication auth = UsernamePasswordAuthenticationToken.authenticated(
            new AccountUser(OWNER, "tester", "unused"), null, List.of());
    private ChatController controller;
    private MockMvc mvc;

    @BeforeEach void setup() {
        when(history.begin(anyString(), anyString(), eq(OWNER))).thenAnswer(i -> new ObjectId());
        when(assistant.stream(anyString(), anyString(), anyString())).thenAnswer(i -> {
            var provider = new ControlledChatStream(); providers.add(provider); return provider;
        });
        configure(tasks::add);
    }
    private void configure(Executor executor) {
        controller = new ChatController(assistant, drafts, json, history, executor);
        mvc = standaloneSetup(controller).setMessageConverters(new StringHttpMessageConverter(StandardCharsets.UTF_8),
                new MappingJackson2HttpMessageConverter(json)).build();
    }
    private MvcResult open(String id) throws Exception {
        return mvc.perform(post("/api/chat/stream").principal(auth).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new ChatRequest(id, "  你好  "))))
                .andExpect(status().isOk()).andExpect(request().asyncStarted()).andReturn();
    }
    private ControlledChatStream start() {
        assertNotNull(tasks.peek()); tasks.remove().run();
        var provider = providers.remove(); assertThat(provider.starts).isEqualTo(1); return provider;
    }
    private static dev.langchain4j.model.chat.response.ChatResponse answer(String text) {
        return dev.langchain4j.model.chat.response.ChatResponse.builder().aiMessage(AiMessage.from(text))
                .finishReason(FinishReason.STOP).build();
    }
    private List<Frame> frames(MvcResult result) throws Exception {
        String wire = result.getResponse().getContentAsString(StandardCharsets.UTF_8).replace("\r\n", "\n");
        List<Frame> frames = new ArrayList<>();
        for (String block : wire.split("\n\n")) {
            if (block.isBlank()) continue;
            String event = block.lines().filter(s -> s.startsWith("event:")).findFirst().orElseThrow().substring(6);
            String data = block.lines().filter(s -> s.startsWith("data:")).findFirst().orElseThrow().substring(5);
            frames.add(new Frame(event, json.readTree(data)));
        }
        return frames;
    }
    private void finished(MvcResult result, String terminal) throws Exception {
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andExpect(header().string("X-Accel-Buffering", "no"));
        assertThat(frames(result).stream().filter(f -> List.of("done", "failed").contains(f.event())).map(Frame::event))
                .containsExactly(terminal);
    }
    private void httpRejected(String id, int code) throws Exception {
        mvc.perform(post("/api/chat/stream").principal(auth).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new ChatRequest(id, "another")))).andExpect(status().is(code));
    }
    private void reusable(String id) throws Exception {
        var next = open(id); var provider = start(); provider.error.accept(new RuntimeException("cleanup"));
        finished(next, "failed");
    }
    private void transportEvent(MvcResult result, String kind) throws Exception {
        var context = (MockAsyncContext) result.getRequest().getAsyncContext();
        var event = new AsyncEvent(context, new IOException("client disconnected"));
        for (var listener : List.copyOf(context.getListeners())) {
            switch (kind) {
                case "timeout" -> listener.onTimeout(event);
                case "error" -> listener.onError(event);
                case "complete" -> listener.onComplete(event);
                default -> throw new IllegalArgumentException(kind);
            }
        }
    }
    private static Content source(String name, int index, String text) {
        return Content.from(TextSegment.from(text, new Metadata().put("source", name).put("index", index)));
    }
    private ToolExecution execution(String name, String result) {
        ToolExecution execution = mock(ToolExecution.class);
        when(execution.request()).thenReturn(ToolExecutionRequest.builder().name(name).arguments("{}").build());
        when(execution.result()).thenReturn(result); return execution;
    }

    @Test void normalWireUsesFullFinalReplyAndSavesBeforeDone() throws Exception {
        var pending = open("  normal  "); var provider = start();
        provider.retrieved.accept(Arrays.asList(null, source("guide.txt", 0, "官方示例资料"),
                source("", 1, "无来源"), source("bad.txt", -1, "非法编号")));
        provider.partial.accept(null); provider.partial.accept(""); provider.partial.accept("你\n好");
        doAnswer(i -> { assertThat(frames(pending)).noneMatch(f -> f.event().equals("done")); return null; })
                .when(history).complete(any(), any());
        provider.completed.accept(answer("你好，完整回答"));
        finished(pending, "done");
        assertThat(frames(pending).stream().map(Frame::event)).containsExactly("status", "sources", "token", "done");
        assertThat(frames(pending).get(2).data().path("text").asText()).isEqualTo("你\n好");
        JsonNode done = frames(pending).get(3).data();
        assertThat(done.path("reply").asText()).isEqualTo("你好，完整回答");
        assertThat(done.path("sources").size()).isEqualTo(1);
        assertThat(done.path("sources").get(0).path("source").asText()).isEqualTo("guide.txt");
        verify(history).begin("normal", "你好", OWNER);
        verify(assistant).stream(eq("normal"), eq("你好"), anyString());
        var saved = ArgumentCaptor.forClass(ChatResponse.class);
        verify(history).complete(any(), saved.capture());
        assertThat(json.<JsonNode>valueToTree(saved.getValue())).isEqualTo(done);
        verify(history, never()).interrupted(any());
        reusable("normal");
    }

    @Test void actualToolIdsAreReloadedAndDeduplicated() throws Exception {
        var pending = open("tools"); var provider = start();
        var draft = new AppointmentDraftResponse("d1", "PENDING_CONFIRMATION", "DEMO001", "内科",
                LocalDate.of(2030, 4, 2), "Asia/Shanghai", null, "请确认");
        when(drafts.findById("d1")).thenReturn(draft);
        provider.tool.accept(execution("queryAppointmentSchedules", "not used"));
        var tool = execution("createAppointmentDraft", "{\"status\":\"PENDING_CONFIRMATION\",\"draft\":{\"draftId\":\"d1\"}}");
        provider.tool.accept(tool); provider.tool.accept(tool);
        provider.completed.accept(answer("请确认草稿")); finished(pending, "done");
        JsonNode result = frames(pending).get(frames(pending).size() - 1).data();
        assertThat(result.path("drafts").size()).isEqualTo(1);
        assertThat(result.path("drafts").get(0)).isEqualTo(json.valueToTree(draft));
        verify(drafts, times(2)).findById("d1");
    }

    @ParameterizedTest @ValueSource(strings = {"broken-json", "{\"status\":\"PENDING_CONFIRMATION\",\"draft\":{}}"})
    void malformedToolResultFailsInsteadOfInventingDraft(String result) throws Exception {
        var pending = open("bad-tool"); var provider = start();
        provider.tool.accept(execution("createAppointmentDraft", result));
        provider.completed.accept(answer("draft claimed")); finished(pending, "failed");
        verify(history, never()).complete(any(), any()); verify(history).interrupted(any());
        verifyNoInteractions(drafts); reusable("bad-tool");
    }

    @Test void providerErrorIsRedactedAndLateCallbacksCannotSaveSuccess() throws Exception {
        var pending = open("error"); var provider = start();
        provider.partial.accept("partial"); provider.error.accept(new RuntimeException("SECRET_PROVIDER_DETAIL"));
        provider.error.accept(new RuntimeException("again")); provider.completed.accept(answer("late"));
        finished(pending, "failed");
        assertThat(pending.getResponse().getContentAsString()).doesNotContain("SECRET_PROVIDER_DETAIL");
        verify(history).interrupted(any()); verify(history, never()).complete(any(), any()); reusable("error");
    }

    @Test void persistenceFailureProducesFailedAndReleasesLeaseEvenIfInterruptWriteFails() throws Exception {
        var pending = open("storage"); var provider = start();
        doThrow(new IllegalStateException("SECRET_DATABASE_DETAIL")).when(history).complete(any(), any());
        doThrow(new IllegalStateException("database unavailable")).when(history).interrupted(any());
        provider.completed.accept(answer("not saved")); finished(pending, "failed");
        assertThat(pending.getResponse().getContentAsString()).doesNotContain("SECRET_DATABASE_DETAIL");
        verify(history).complete(any(), any()); verify(history).interrupted(any()); reusable("storage");
    }

    @Test void lengthLimitIsNotSuccessfulCompletion() throws Exception {
        var pending = open("length"); var provider = start();
        provider.completed.accept(dev.langchain4j.model.chat.response.ChatResponse.builder()
                .aiMessage(AiMessage.from("partial")).finishReason(FinishReason.LENGTH).build());
        finished(pending, "failed"); verify(history, never()).complete(any(), any());
        verify(history).interrupted(any()); reusable("length");
    }

    @Test void blankFinalResponseFails() throws Exception {
        var pending = open("blank"); var provider = start();
        provider.completed.accept(answer("  ")); finished(pending, "failed");
        verify(history, never()).complete(any(), any()); reusable("blank");
    }

    @Test void modelFactoryFailureEndsTurnAndReleasesLease() throws Exception {
        var pending = open("factory");
        when(assistant.stream(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("factory"));
        tasks.remove().run(); finished(pending, "failed"); verify(history).interrupted(any());
        assertDoesNotThrow(() -> controller.stream(new ChatRequest("factory", "retry"), auth));
        tasks.remove().run();
    }

    @Test void modelStartFailureEndsTurn() throws Exception {
        var provider = new ControlledChatStream(); provider.startFailure = new IllegalStateException("start");
        when(assistant.stream(anyString(), anyString(), anyString())).thenReturn(provider);
        var pending = open("start"); tasks.remove().run(); finished(pending, "failed");
        verify(history).interrupted(any()); verify(history, never()).complete(any(), any());
    }

    @Test void rejectedExecutorReturns503AndCanRetrySameConversation() throws Exception {
        var reject = new AtomicBoolean(true);
        configure(task -> { if (reject.get()) throw new RejectedExecutionException(); tasks.add(task); });
        httpRejected("queue", 503); verify(history).interrupted(any()); verifyNoInteractions(assistant);
        reject.set(false); reusable("queue");
    }

    @Test void historyBeginFailureReleasesLeaseBeforeModelStarts() throws Exception {
        when(history.begin(eq("begin"), anyString(), eq(OWNER))).thenThrow(new IllegalStateException("begin"));
        assertThrows(IllegalStateException.class, () -> controller.stream(new ChatRequest("begin", "hi"), auth));
        verifyNoInteractions(assistant); assertThat(tasks).isEmpty();
        when(history.begin(eq("begin"), anyString(), eq(OWNER))).thenReturn(new ObjectId()); reusable("begin");
    }

    @ParameterizedTest @ValueSource(strings = {"{}", "{\"conversationId\":\"x\",\"message\":\" \"}", "{\"conversationId\":\" \",\"message\":\"hi\"}"})
    void invalidRequestReturns400WithoutStartingHistory(String body) throws Exception {
        mvc.perform(post("/api/chat/stream").principal(auth).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()); verifyNoInteractions(history, assistant);
    }

    @Test void missingAuthenticationReturns401AndDoesNotLeakLease() throws Exception {
        mvc.perform(post("/api/chat/stream").contentType(MediaType.APPLICATION_JSON)
                .content("{\"conversationId\":\"auth\",\"message\":\"hi\"}")).andExpect(status().isUnauthorized());
        verifyNoInteractions(history, assistant); reusable("auth");
    }

    @Test void pendingStreamBlocksBothSyncAndStreamDuplicates() throws Exception {
        var pending = open("same"); var provider = start(); httpRejected(" same ", 409);
        mvc.perform(post("/api/chat").principal(auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"conversationId\":\"same\",\"message\":\"again\"}")).andExpect(status().isConflict());
        verify(history, times(1)).begin(anyString(), anyString(), anyString());
        provider.completed.accept(answer("done")); finished(pending, "done"); reusable("same");
    }

    @Test void eightTurnCapacityRejectsNinthAndTerminalDuplicatesCannotInflateIt() throws Exception {
        var first = open("finished"); var done = start(); done.completed.accept(answer("done"));
        done.completed.accept(answer("duplicate")); done.error.accept(new RuntimeException("late")); finished(first, "done");
        List<MvcResult> pending = new ArrayList<>(); List<ControlledChatStream> running = new ArrayList<>();
        for (int i = 0; i < 8; i++) { pending.add(open("slot-" + i)); running.add(start()); }
        httpRejected("overflow", 429);
        running.get(0).completed.accept(answer("released")); finished(pending.get(0), "done");
        reusable("overflow"); // 429 must have removed its conversation-id claim.
        for (int i = 1; i < 8; i++) { running.get(i).error.accept(new RuntimeException("cleanup")); finished(pending.get(i), "failed"); }
    }

    @ParameterizedTest @ValueSource(strings = {"error", "complete", "timeout"})
    void disconnectedTransportKeepsLeaseUntilRealCompletionAndStillSaves(String kind) throws Exception {
        var pending = open("disconnect"); var provider = start(); transportEvent(pending, kind);
        String before = pending.getResponse().getContentAsString();
        httpRejected("disconnect", 409); verify(history, never()).interrupted(any());
        provider.partial.accept("late token"); provider.completed.accept(answer("saved after disconnect"));
        verify(history).complete(any(), argThat(r -> r.reply().equals("saved after disconnect")));
        assertThat(pending.getResponse().getContentAsString()).isEqualTo(before);
        reusable("disconnect");
    }

    @Test void disconnectThenModelErrorMarksInterruptedAndReleasesLease() throws Exception {
        var pending = open("disconnected-error"); var provider = start(); transportEvent(pending, "error");
        httpRejected("disconnected-error", 409); provider.error.accept(new RuntimeException("model"));
        verify(history).interrupted(any()); verify(history, never()).complete(any(), any()); reusable("disconnected-error");
    }

    @Test void disconnectBeforeQueuedTaskPreventsModelStart() throws Exception {
        var pending = open("not-started"); transportEvent(pending, "complete");
        tasks.remove().run(); verifyNoInteractions(assistant); verify(history).interrupted(any()); reusable("not-started");
    }

    @Test void disconnectDuringModelPreparationPreventsTokenStreamStart() throws Exception {
        var pending = open("preparing"); var provider = new ControlledChatStream();
        when(assistant.stream(anyString(), anyString(), anyString())).thenAnswer(i -> {
            transportEvent(pending, "complete"); return provider;
        });
        tasks.remove().run(); assertThat(provider.starts).isZero(); verify(history).interrupted(any());
        assertDoesNotThrow(() -> controller.stream(new ChatRequest("preparing", "retry"), auth));
    }

    @Test void completionAndErrorRaceHaveOneTerminalState() throws Exception {
        var pending = open("race"); var provider = start(); var barrier = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var success = pool.submit(() -> { barrier.await(3, TimeUnit.SECONDS); provider.completed.accept(answer("answer")); return null; });
            var failure = pool.submit(() -> { barrier.await(3, TimeUnit.SECONDS); provider.error.accept(new RuntimeException("race")); return null; });
            success.get(5, TimeUnit.SECONDS); failure.get(5, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS)); }
        mvc.perform(asyncDispatch(pending)).andExpect(status().isOk());
        assertThat(frames(pending).stream().filter(f -> List.of("done", "failed").contains(f.event()))).hasSize(1);
        long terminalWrites = mockingDetails(history).getInvocations().stream()
                .filter(i -> List.of("complete", "interrupted").contains(i.getMethod().getName())).count();
        assertThat(terminalWrites).isEqualTo(1); reusable("race");
    }

    @Test void simultaneousSameConversationRequestsStartOnlyOneTurn() throws Exception {
        var pool = Executors.newFixedThreadPool(8); var barrier = new CyclicBarrier(8);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) results.add(pool.submit(() -> {
                barrier.await(3, TimeUnit.SECONDS);
                try { controller.stream(new ChatRequest("concurrent", "hi"), auth); return 200; }
                catch (ResponseStatusException e) { return e.getStatusCode().value(); }
            }));
            List<Integer> codes = new ArrayList<>();
            for (var result : results) codes.add(result.get(5, TimeUnit.SECONDS));
            assertThat(codes).filteredOn(c -> c == 200).hasSize(1);
            assertThat(codes).filteredOn(c -> c == 409).hasSize(7);
            verify(history).begin("concurrent", "hi", OWNER);
            var provider = start(); provider.error.accept(new RuntimeException("cleanup"));
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS)); }
    }

    @Test void savingMustFinishBeforeConversationCanBeReused() throws Exception {
        var pending = open("saving"); var provider = start();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(i -> { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return null; })
                .when(history).complete(any(), any());
        var pool = Executors.newSingleThreadExecutor();
        try {
            var completion = pool.submit(() -> provider.completed.accept(answer("answer")));
            assertTrue(entered.await(3, TimeUnit.SECONDS)); httpRejected("saving", 409);
            assertThat(frames(pending)).noneMatch(f -> f.event().equals("done"));
            release.countDown(); completion.get(5, TimeUnit.SECONDS); finished(pending, "done"); reusable("saving");
        } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS)); }
    }
    private record Frame(String event, JsonNode data) {}
}
