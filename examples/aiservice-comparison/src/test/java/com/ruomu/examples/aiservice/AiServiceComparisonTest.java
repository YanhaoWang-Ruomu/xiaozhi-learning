package com.ruomu.examples.aiservice;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = LabApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AiServiceComparisonTest {
    @Autowired ManualAssistant manual;
    @Autowired DeclarativeAssistant declarative;
    @Autowired RecordingChatModel model;
    @Autowired LabConfiguration.LabTools tools;
    @Autowired ApplicationContext context;
    private static final String DATE = "2026-10-06";
    @BeforeEach void resetRequests() { model.clear(); }
    private String id() { return UUID.randomUUID().toString(); }

    @Test void starterRegistersExactlyOneDeclarativeSingleton() {
        assertEquals(1, context.getBeansOfType(DeclarativeAssistant.class).size());
        assertSame(declarative, context.getBean(DeclarativeAssistant.class));
        assertEquals(1, context.getBeansOfType(ManualAssistant.class).size());
        assertTrue(context.containsBean("unusedModel"));
        assertTrue(context.containsBean("unrelatedTools"));
    }
    @Test void bothWaysSendSamePromptAndResolveVariables() {
        String memoryId = id();
        assertEquals(manual.chat(memoryId, "你好", DATE), declarative.chat(memoryId, "你好", DATE));
        var requests = model.requests();
        assertEquals(2, requests.size());
        assertEquals(requests.get(0).messages(), requests.get(1).messages());
        var prompt = (SystemMessage) requests.get(1).messages().get(0);
        assertTrue(prompt.text().contains(DATE));
        assertFalse(prompt.text().contains("{{businessDate}}"));
        // 两个模型和多个工具Bean同时存在，实际使用的模型和工具仍由名称明确决定。
        assertEquals(List.of("readDemoRule"), requests.get(1).toolSpecifications().stream().map(t -> t.name()).toList());
    }
    @Test void conversationsAndTwoAssistantMemoryStoresRemainSeparate() {
        String a = id(), b = id();
        assertTrue(declarative.chat(a, "A1", DATE).endsWith("USER_MESSAGES=1"));
        assertTrue(declarative.chat(a, "A2", DATE).endsWith("USER_MESSAGES=2"));
        assertTrue(declarative.chat(b, "B1", DATE).endsWith("USER_MESSAGES=1"));
        assertTrue(manual.chat(a, "manual A1", DATE).endsWith("USER_MESSAGES=1"));
        var isolated = model.requests().get(2).messages();
        assertFalse(isolated.stream().filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast).anyMatch(m -> m.singleText().startsWith("A")));
    }
    @Test void bothWaysActuallyExecuteOnlySelectedToolWithInjectedMemoryId() {
        String memoryId = id();
        int before = tools.calls();
        String left = manual.chat(memoryId, "查询演示规则", DATE);
        String right = declarative.chat(memoryId, "查询演示规则", DATE);
        assertEquals(left, right);
        assertTrue(right.contains("MEMORY_ID=" + memoryId));
        assertTrue(right.contains("MANUAL_CONFIRMATION_REQUIRED"));
        assertEquals(before + 2, tools.calls());
        assertEquals(4, model.requests().size(), "每个助手一轮工具请求加一轮工具结果回复");
        for (var request : model.requests()) {
            assertEquals(List.of("readDemoRule"), request.toolSpecifications().stream().map(t -> t.name()).toList());
        }
    }
    @Test void configuredMemoryIsAWindowRatherThanFullHistory() {
        String memoryId = id();
        for (int i = 0; i < 12; i++) declarative.chat(memoryId, "message-" + i, DATE);
        var last = model.requests().get(11).messages();
        assertTrue(last.size() <= 8);
        assertEquals(1, last.stream().filter(SystemMessage.class::isInstance).count());
        assertFalse(last.stream().filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast).anyMatch(m -> m.singleText().equals("message-0")));
    }
}
