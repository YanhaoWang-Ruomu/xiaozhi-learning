package com.ruomu.xiaozhi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.*;
import dev.langchain4j.model.chat.request.*;
import dev.langchain4j.model.chat.response.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Recomputes tools for each model call, including calls inside the AI Services tool loop. */
public final class StagedAppointmentModels {
    private static final String QUERY="queryAppointmentSessions";
    private static final String CREATE="createAppointmentDraft";
    private static final Set<String> READ=Set.of(QUERY,"queryAppointmentRule","queryAppointmentSchedules");
    private static final ObjectMapper JSON=new ObjectMapper();
    enum Stage { QUERY, DRAFT, FINISH }
    private StagedAppointmentModels() {}

    static Stage stage(ChatRequest request) {
        int boundary=-1;
        for(int i=0;i<request.messages().size();i++)
            if(request.messages().get(i) instanceof UserMessage) boundary=i;
        if(boundary<0) return Stage.FINISH;
        Stage stage=Stage.QUERY;
        for(int i=boundary+1;i<request.messages().size();i++) {
            if(!(request.messages().get(i) instanceof ToolExecutionResultMessage result)) continue;
            if(CREATE.equals(result.toolName())) return Stage.FINISH;
            if(!QUERY.equals(result.toolName())) continue;
            try {
                var data=JSON.readTree(result.text());
                if("QUERY_FAILED".equals(data.path("status").asText())) return Stage.FINISH;
                boolean available=false;
                for(var slot:data.path("data").path("sessions"))
                    if(Set.of("AVAILABLE","NOT_RELEASED").contains(slot.path("bookingStatus").asText())
                            && slot.path("referenceRemaining").asLong(0)>0) available=true;
                stage="DEMO_DATA".equals(data.path("status").asText())
                        && data.path("data").path("bookingEnabled").asBoolean(false)
                        && !data.path("queryReceipt").asText("").isBlank() && available ? Stage.DRAFT : Stage.QUERY;
            } catch(Exception malformed) { return Stage.FINISH; }
        }
        return stage;
    }

    static ChatRequest filter(ChatRequest request) {
        Stage stage=stage(request);
        var original=request.toolSpecifications()==null?List.<dev.langchain4j.agent.tool.ToolSpecification>of():request.toolSpecifications();
        var allowed=original.stream().filter(t->stage!=Stage.FINISH &&
                (READ.contains(t.name()) || (stage==Stage.DRAFT && CREATE.equals(t.name())))).toList();
        var parameters=ChatRequestParameters.builder().overrideWith(request.parameters())
                .toolSpecifications(allowed).toolChoice(ToolChoice.AUTO).build();
        return ChatRequest.builder().messages(modelMessages(request,stage)).parameters(parameters).build();
    }

    static List<ChatMessage> modelMessages(ChatRequest request, Stage stage) {
        int boundary=-1;
        for(int i=0;i<request.messages().size();i++)
            if(request.messages().get(i) instanceof UserMessage) boundary=i;
        String instruction="\n【本次模型调用的程序阶段约束】\n"+switch(stage) {
            case QUERY -> "当前是查询阶段。只可调用 queryAppointmentSessions、queryAppointmentRule、queryAppointmentSchedules。"
                    +"createAppointmentDraft 尚未开放，禁止调用或编造此工具调用。准备草稿前必须先调用 queryAppointmentSessions，等待程序开放创建阶段。旧轮次的结果、编号和凭据均不能跳过这一步。";
            case DRAFT -> "本轮查询成功且返回可准备的场次，创建工具现已开放。仅在需求明确且用户已选择匹配场次时，"
                    +"使用本轮 queryReceipt 创建一份草稿；不能替用户选择或确认预约。";
            case FINISH -> "本轮工具阶段已结束，当前没有任何可用工具。只依据实际结果简短回答，不再请求工具，"
                    +"不自行声称未执行的操作成功。";
        };
        List<ChatMessage> messages=new ArrayList<>();
        boolean system=false;
        for(int i=0;i<request.messages().size();i++) {
            ChatMessage message=request.messages().get(i);
            if(message instanceof SystemMessage original) {
                messages.add(SystemMessage.from(original.text()+instruction));system=true;continue;
            }
            if(i<boundary && message instanceof ToolExecutionResultMessage) continue;
            if(i<boundary && message instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                if(ai.text()!=null && !ai.text().isBlank()) messages.add(AiMessage.from(ai.text()));
                continue;
            }
            messages.add(message);
        }
        if(!system) messages.add(0,SystemMessage.from(instruction));
        return messages;
    }

    static void validate(ChatRequest filtered, ChatResponse response) {
        Set<String> names=new HashSet<>();
        filtered.toolSpecifications().forEach(t->names.add(t.name()));
        int writes=0;
        if(response==null || response.aiMessage()==null) throw new IllegalStateException("Empty model response");
        var calls=response.aiMessage().toolExecutionRequests();
        if(calls==null) return;
        for(var call:calls) {
            if(!names.contains(call.name()) || (CREATE.equals(call.name()) && ++writes>1))
                throw new IllegalStateException("Model requested a tool outside the current appointment stage");
        }
    }

    public static ChatLanguageModel sync(ChatLanguageModel delegate) {
        return new ChatLanguageModel() {
            public ChatRequestParameters defaultRequestParameters(){return delegate.defaultRequestParameters();}
            public Set<Capability> supportedCapabilities(){return delegate.supportedCapabilities();}
            public ChatResponse chat(ChatRequest request) {
                var filtered=filter(request);
                var response=delegate.chat(filtered);
                validate(filtered,response);
                return response;
            }
        };
    }

    public static StreamingChatLanguageModel streaming(StreamingChatLanguageModel delegate) {
        return new StreamingChatLanguageModel() {
            public ChatRequestParameters defaultRequestParameters(){return delegate.defaultRequestParameters();}
            public Set<Capability> supportedCapabilities(){return delegate.supportedCapabilities();}
            public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
                var filtered=filter(request);
                var ended=new AtomicBoolean();
                var guarded=new StreamingChatResponseHandler() {
                    public void onPartialResponse(String text){if(!ended.get())handler.onPartialResponse(text);}
                    public void onCompleteResponse(ChatResponse response) {
                        if(!ended.compareAndSet(false,true)) return;
                        try { validate(filtered,response); }
                        catch(RuntimeException error){handler.onError(error);return;}
                        handler.onCompleteResponse(response);
                    }
                    public void onError(Throwable error){if(ended.compareAndSet(false,true))handler.onError(error);}
                };
                try { delegate.chat(filtered,guarded); }
                catch(RuntimeException error){guarded.onError(error);}
            }
        };
    }
}