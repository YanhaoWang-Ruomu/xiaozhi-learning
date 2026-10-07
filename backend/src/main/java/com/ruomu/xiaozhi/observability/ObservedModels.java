package com.ruomu.xiaozhi.observability;
import dev.langchain4j.model.chat.*;
import dev.langchain4j.model.chat.request.*;
import dev.langchain4j.model.chat.response.*;
import io.opentelemetry.context.Context;

public final class ObservedModels {
    private static void usage(AiTelemetry.Operation op, ChatResponse response) {
        if(response.tokenUsage()!=null) {
            var u=response.tokenUsage();
            if(u.inputTokenCount()!=null)op.span.setAttribute("gen_ai.usage.input_tokens",u.inputTokenCount());
            if(u.outputTokenCount()!=null)op.span.setAttribute("gen_ai.usage.output_tokens",u.outputTokenCount());
        }
        if(response.finishReason()!=null)op.span.setAttribute("gen_ai.finish_reason",response.finishReason().name());
    }
    public static ChatLanguageModel sync(ChatLanguageModel delegate) {
        return new ChatLanguageModel() {
            public ChatRequestParameters defaultRequestParameters(){return delegate.defaultRequestParameters();}
            public java.util.Set<Capability> supportedCapabilities(){return delegate.supportedCapabilities();}
            public ChatResponse chat(ChatRequest request) {
                try(var op=AiTelemetry.start("model.generate");var scope=op.scope()) {
                    try{var response=delegate.chat(request);usage(op,response);return response;}
                    catch(RuntimeException e){op.fail(e);throw e;}
                }
            }
        };
    }
    public static StreamingChatLanguageModel streaming(StreamingChatLanguageModel delegate) {
        return new StreamingChatLanguageModel() {
            public ChatRequestParameters defaultRequestParameters(){return delegate.defaultRequestParameters();}
            public java.util.Set<Capability> supportedCapabilities(){return delegate.supportedCapabilities();}
            public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
                Context parent=Context.current();
                var op=AiTelemetry.start("model.stream");
                var once=new java.util.concurrent.atomic.AtomicBoolean();
                try(var scope=op.scope()) {
                    delegate.chat(request,new StreamingChatResponseHandler(){
                        public void onPartialResponse(String token){try(var s=parent.makeCurrent()){handler.onPartialResponse(token);}}
                        public void onCompleteResponse(ChatResponse response){
                            if(!once.compareAndSet(false,true))return;
                            usage(op,response);op.close();
                            try(var s=parent.makeCurrent()){handler.onCompleteResponse(response);}
                        }
                        public void onError(Throwable error){
                            if(!once.compareAndSet(false,true))return;
                            op.fail(error);op.close();
                            try(var s=parent.makeCurrent()){handler.onError(error);}
                        }
                    });
                } catch(RuntimeException e){op.fail(e);op.close();throw e;}
            }
        };
    }
}
