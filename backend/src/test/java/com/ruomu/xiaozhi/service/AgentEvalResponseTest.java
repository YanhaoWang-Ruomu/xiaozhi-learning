package com.ruomu.xiaozhi.service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class AgentEvalResponseTest {
    static class Stream implements TokenStream {
        Consumer<String> partial;Consumer<ToolExecution> tool;Consumer<ChatResponse> complete;Consumer<Throwable> error;
        Runnable run=()->{};
        public TokenStream onPartialResponse(Consumer<String> c){partial=c;return this;}
        public TokenStream onRetrieved(Consumer<List<Content>> c){return this;}
        public TokenStream onToolExecuted(Consumer<ToolExecution> c){tool=c;return this;}
        public TokenStream onCompleteResponse(Consumer<ChatResponse> c){complete=c;return this;}
        public TokenStream onError(Consumer<Throwable> c){error=c;return this;}
        public TokenStream ignoreErrors(){return this;}
        public void start(){run.run();}
    }
    private ChatResponse response(String text){return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();}
    @Test void capturesPartialsAndFinalSeparately() {
        var s=new Stream();s.run=()->{s.partial.accept("你");s.partial.accept("好");s.complete.accept(response("你好"));};
        var r=AgentEvalResponse.collect(s,100);
        assertEquals(2,r.partialCount());assertEquals("你好",r.streamedText());assertEquals("你好",r.content());
    }
    @Test void capturesToolExecutionAndIgnoresLateCallbacks() {
        var s=new Stream();
        var t=ToolExecution.builder().request(dev.langchain4j.agent.tool.ToolExecutionRequest.builder().name("queryAppointmentSessions").arguments("{}").build()).result("{}").build();
        s.run=()->{s.tool.accept(t);s.partial.accept("结果");s.complete.accept(response("结果"));s.partial.accept("迟到");s.tool.accept(t);s.error.accept(new RuntimeException());};
        var r=AgentEvalResponse.collect(s,100);
        assertEquals(1,r.toolExecutions().size());assertEquals("结果",r.streamedText());
    }
    @Test void modelErrorDoesNotLookLikeSuccess() {
        var s=new Stream();s.run=()->s.error.accept(new IllegalStateException("test"));
        assertThrows(IllegalStateException.class,()->AgentEvalResponse.collect(s,100));
    }
    @Test void synchronousStartFailurePropagates() {
        var s=new Stream();s.run=()->{throw new IllegalArgumentException("test");};
        assertThrows(IllegalArgumentException.class,()->AgentEvalResponse.collect(s,100));
    }
    @Test void missingCompletionTimesOutAndIgnoresLaterPartial() {
        var s=new Stream();
        assertThrows(IllegalStateException.class,()->AgentEvalResponse.collect(s,1));
        assertDoesNotThrow(()->s.partial.accept("late"));
    }
    @Test void nullCompletionIsAnError() {
        var s=new Stream();s.run=()->s.complete.accept(null);
        assertThrows(IllegalStateException.class,()->AgentEvalResponse.collect(s,100));
    }
}
