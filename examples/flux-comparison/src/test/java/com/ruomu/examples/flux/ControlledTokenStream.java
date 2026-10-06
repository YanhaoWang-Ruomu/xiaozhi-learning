package com.ruomu.examples.flux;

import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;
import java.util.List;
import java.util.function.Consumer;

/** A manually driven provider boundary: no API keys, network, database or actual appointment tools. */
final class ControlledTokenStream implements TokenStream {
    int starts;
    Consumer<String> partial = value -> {};
    Consumer<List<Content>> retrieved = value -> {};
    Consumer<ToolExecution> tool = value -> {};
    Consumer<ChatResponse> completed = value -> {};
    Consumer<Throwable> error = value -> {};
    public TokenStream onPartialResponse(Consumer<String> callback) { partial = callback; return this; }
    public TokenStream onRetrieved(Consumer<List<Content>> callback) { retrieved = callback; return this; }
    public TokenStream onToolExecuted(Consumer<ToolExecution> callback) { tool = callback; return this; }
    public TokenStream onCompleteResponse(Consumer<ChatResponse> callback) { completed = callback; return this; }
    public TokenStream onError(Consumer<Throwable> callback) { error = callback; return this; }
    public TokenStream ignoreErrors() { error = value -> {}; return this; }
    public void start() { starts++; }
}
