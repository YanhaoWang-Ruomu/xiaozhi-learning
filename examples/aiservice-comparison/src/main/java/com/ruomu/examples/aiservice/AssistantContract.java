package com.ruomu.examples.aiservice;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/** 两种创建方式继承同一接口，避免提示词或方法定义不同影响对照。 */
public interface AssistantContract {
    @SystemMessage("你是装配教学助手。上海业务日期：{{businessDate}}。只能解释演示规则，不能办理预约。")
    String chat(@MemoryId String conversationId,
                @UserMessage String message,
                @V("businessDate") String businessDate);
}
