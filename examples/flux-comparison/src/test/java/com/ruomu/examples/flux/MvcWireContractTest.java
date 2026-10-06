package com.ruomu.examples.flux;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import reactor.core.publisher.Flux;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class MvcWireContractTest {
    @RestController
    static class FixtureController {
        static List<ServerSentEvent<Object>> events() {
            var source = Map.<String, Object>of("source", "knowledge/demo.txt", "index", 0, "text", "演示资料");
            return List.of(
                    EventFluxBridge.event("status", Map.of("message", "开始")),
                    EventFluxBridge.event("sources", Map.of("sources", List.of(source))),
                    EventFluxBridge.event("token", Map.of("text", "你好")),
                    EventFluxBridge.event("done", new EventFluxBridge.Result("你好", List.of(), List.of(source))));
        }
        @GetMapping(value = "/fixture/flux", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        Flux<ServerSentEvent<Object>> flux() { return Flux.fromIterable(events()); }

        @GetMapping(value = "/fixture/emitter", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        SseEmitter emitter() throws IOException {
            SseEmitter emitter = new SseEmitter(5000L);
            for (var event : events()) emitter.send(SseEmitter.event().name(event.event()).data(event.data()));
            emitter.complete();
            return emitter;
        }
    }

    @Test void mvcSerializesFluxAndEmitterWithSameNamedEventsAndJson() throws Exception {
        var mvc = standaloneSetup(new FixtureController()).build();
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(8);
        executor.setThreadNamePrefix("flux-lab-");
        executor.initialize();
        var adapter = mvc.getDispatcherServlet().getWebApplicationContext()
                .getBean(RequestMappingHandlerAdapter.class);
        adapter.setTaskExecutor(executor);
        // Standalone MockMvc already built return-value handlers; rebuild with the configured executor.
        adapter.setReturnValueHandlers(null);
        adapter.afterPropertiesSet();
        try {
            var json = new ObjectMapper();
            for (String route : List.of("flux", "emitter")) {
                var pending = mvc.perform(get("/fixture/" + route)).andExpect(request().asyncStarted()).andReturn();
                var response = mvc.perform(asyncDispatch(pending)).andExpect(status().isOk())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)).andReturn().getResponse();
                String wire = response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8).replace("\r\n", "\n");
                String[] frames = wire.strip().split("\n\n");
                var expected = FixtureController.events();
                assertThat(frames).hasSize(expected.size());
                for (int i = 0; i < frames.length; i++) {
                    assertThat(frames[i]).contains("event:" + expected.get(i).event());
                    String data = frames[i].lines().filter(line -> line.startsWith("data:")).findFirst().orElseThrow().substring(5);
                    assertThat(json.readTree(data)).isEqualTo(json.valueToTree(expected.get(i).data()));
                }
            }
            System.out.println("FLUX_COMPARISON_OK mvc=PASS lifecycle=OFFLINE");
        } finally {
            executor.shutdown();
        }
    }
}
