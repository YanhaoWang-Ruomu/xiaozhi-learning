package com.ruomu.xiaozhi.service;

import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;
import java.util.*;
import java.util.concurrent.*;

/** Paid-evaluation adapter only. Does not replace the application's SSE controller. */
final class AgentEvalResponse {
    record Answer(String content, List<ToolExecution> toolExecutions, TokenUsage tokenUsage,
                  int partialCount, String streamedText) {}
    static Answer call(ChatAssistant assistant, String id, String message, String date, String transport) {
        if ("STREAM".equals(transport)) return collect(assistant.stream(id,message,date),300_000);
        var result=assistant.chat(id,message,date);
        return new Answer(result.content(),result.toolExecutions(),result.tokenUsage(),0,"");
    }
    static Answer collect(TokenStream stream,long timeoutMs) {
        var done=new CompletableFuture<Answer>();
        var tools=new ArrayList<ToolExecution>();
        var partials=new StringBuilder();
        int[] count={0};
        Object lock=new Object();
        try {
            stream.onPartialResponse(text->{synchronized(lock) {
                if(!done.isDone()){partials.append(text);count[0]++;}
            }}).onToolExecuted(tool->{synchronized(lock) {
                if(!done.isDone())tools.add(tool);
            }}).onCompleteResponse(response->{synchronized(lock) {
                if(done.isDone())return;
                if(response==null||response.aiMessage()==null) {
                    done.completeExceptionally(new IllegalStateException("Missing stream response"));return;
                }
                done.complete(new Answer(response.aiMessage().text(),List.copyOf(tools),
                        response.tokenUsage(),count[0],partials.toString()));
            }}).onError(done::completeExceptionally).start();
            return done.get(timeoutMs,TimeUnit.MILLISECONDS);
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();done.completeExceptionally(e);
            throw new IllegalStateException("Evaluation interrupted",e);
        } catch(ExecutionException|TimeoutException e) {
            done.completeExceptionally(e);
            // beta3 has no cancellation handle here; the caller aborts the paid evaluation.
            throw new IllegalStateException("Stream evaluation failed",e);
        }
    }
}
