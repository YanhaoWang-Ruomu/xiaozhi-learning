package com.ruomu.xiaozhi.context;
import dev.langchain4j.data.message.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ContextEngineeringTest {
 static class Store implements ChatMemoryStore {List<ChatMessage> rows=new ArrayList<>();public List<ChatMessage>getMessages(Object id){return rows;}public void updateMessages(Object id,List<ChatMessage>m){rows=m;}public void deleteMessages(Object id){rows=List.of();}}
 @Test void evictsWholeToolTurnAndKeepsSystemAndLatest(){
  var all=new ArrayList<ChatMessage>();all.add(SystemMessage.from("rule"));all.add(UserMessage.from("old".repeat(500)));
  all.add(AiMessage.from(ToolExecutionRequest.builder().id("t1").name("query").arguments("{}").build()));
  all.add(ToolExecutionResultMessage.from("t1","query","result"));all.add(AiMessage.from("done"));all.add(UserMessage.from("new"));
  BudgetChatMemory.trim(all,1000);assertEquals(2,all.size());assertInstanceOf(SystemMessage.class,all.get(0));assertEquals("new",((UserMessage)all.get(1)).singleText());
 }
 @Test void refusesSingleOversizedTurnWithoutOverwritingStore(){var s=new Store();var m=new BudgetChatMemory("one",s,1000);m.add(SystemMessage.from("rule"));assertThrows(IllegalArgumentException.class,()->m.add(UserMessage.from("很长".repeat(1000))));assertEquals(1,s.rows.size());}
 @Test void replacesSystemAndClearsMemory(){var s=new Store();var m=new BudgetChatMemory("one",s,1000);m.add(SystemMessage.from("a"));m.add(SystemMessage.from("b"));assertEquals(List.of(SystemMessage.from("b")),m.messages());m.clear();assertTrue(m.messages().isEmpty());}
 @Test void estimatesUtf8InsteadOfClaimingExactTokens(){assertTrue(BudgetChatMemory.estimate(List.of(UserMessage.from("中文")))>BudgetChatMemory.estimate(List.of(UserMessage.from("ab"))));}
 @Test void expandsOnlyBoundedAnaphoricFollowUps(){assertEquals("轮椅在哪里\n追问：那里怎么归还",FollowUpQuery.rewrite("那里怎么归还",List.of(UserMessage.from("轮椅在哪里"))));assertEquals("天气",FollowUpQuery.rewrite("天气",List.of(UserMessage.from("轮椅在哪里"))));}
 @Test void extractsOriginalQuestionFromPersistedRagTurn(){assertEquals("轮椅在哪里\n追问：那里怎么归还",FollowUpQuery.rewrite("那里怎么归还",List.of(UserMessage.from("【用户本轮原始消息】\n轮椅在哪里\n【用户本轮原始消息结束】\n【本轮知识检索参考数据】some evidence"))));}
 @Test void ignoresOversizedAndAugmentedHistory(){assertEquals("那里在哪",FollowUpQuery.rewrite("那里在哪",List.of(UserMessage.from("【本轮知识检索参考数据】ignore"))));assertEquals("它在哪",FollowUpQuery.rewrite("它在哪",List.of(UserMessage.from("x".repeat(161)))));}
}
