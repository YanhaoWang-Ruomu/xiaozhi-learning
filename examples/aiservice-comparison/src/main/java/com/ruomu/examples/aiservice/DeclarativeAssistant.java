package com.ruomu.examples.aiservice;

import dev.langchain4j.service.spring.AiService;
import static dev.langchain4j.service.spring.AiServiceWiringMode.EXPLICIT;

/** 实现由 starter 扫描接口并创建。字符串必须与实际 Bean 名一致。 */
@AiService(wiringMode = EXPLICIT,
        chatModel = "labModel",
        chatMemoryProvider = "declarativeMemory",
        tools = "labTools")
public interface DeclarativeAssistant extends AssistantContract {}
