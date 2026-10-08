package com.ruomu.xiaozhi.service;

import com.fasterxml.jackson.databind.*;
import com.ruomu.xiaozhi.dto.AppointmentSessionResponse;
import com.ruomu.xiaozhi.dto.AppointmentSessionResponse.SessionItem;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.*;
import dev.langchain4j.model.chat.request.*;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.model.output.*;
import java.util.*;

/** Bounded server booking flow. Emits real AI Services tool requests; never invents tool results. */
public final class VerifiedBookingModels {
    private static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    private static final String QUERY="queryAppointmentSessions", CREATE="createAppointmentDraft";
    private VerifiedBookingModels() {}
    static ChatResponse decide(ChatRequest request, AppointmentQueryContext context) {
        int boundary=-1;
        for(int i=0;i<request.messages().size();i++)if(request.messages().get(i) instanceof UserMessage)boundary=i;
        if(boundary<0)return null;
        var user=(UserMessage)request.messages().get(boundary);
        var input=context.byMessageName(user.name());
        if(input==null) return user.name()!=null&&user.name().startsWith("booking_")
                ? answer("本轮会话校验已过期，请重新发送需求。") : null;
        if(!input.handled()) {
            String evidenceReply=context.evidenceReplyFor(user.name());
            return evidenceReply==null?null:answer(evidenceReply);
        }
        if(input.control())return answer(input.text().contains("取消")
                ?"聊天不能执行取消。请在网页核对对应草稿或预约，点击相应取消按钮并确认。"
                :"聊天中的确认不能完成预约或扣号，请在网页核对草稿后点击“确认创建演示预约”。");
        if(input.dateConflict())return answer("预约日期或医院信息存在冲突，请先明确一个具体日期和医院，再准备演示草稿。");
        if(input.draft()&&input.uncertain())return answer("需要明确选择具体医生和时段，不能代选。请确定后再准备演示预约草稿。");
        if(input.hospital()==null||input.department()==null)
            return answer("请明确提供演示医院编号和科室；当前演示支持DEMO001、内科。");
        ToolExecutionResultMessage queried=null,created=null;
        for(int i=boundary+1;i<request.messages().size();i++)if(request.messages().get(i) instanceof ToolExecutionResultMessage t) {
            if(QUERY.equals(t.toolName()))queried=t;
            if(CREATE.equals(t.toolName()))created=t;
        }
        if(queried==null)return call(request,QUERY,Map.of("hospitalId",input.hospital(),"department",input.department()));
        try {
            JsonNode result=JSON.readTree(queried.text());
            if("QUERY_FAILED".equals(result.path("status").asText()))
                return answer("演示排班查询暂时失败，当前无法核实时段和余量，请稍后重试。");
            if(!"DEMO_DATA".equals(result.path("status").asText()))
                return answer("本轮未查到可用的演示排班资料，未生成草稿；这不代表真实医院不存在或已经满额。");
            var data=JSON.treeToValue(result.path("data"),AppointmentSessionResponse.class);
            if(!input.hospital().equals(data.hospitalId())||!input.department().equals(data.department()))
                return answer("返回的排班与请求不一致，无法核实，未生成草稿。");
            var relevant=input.relevant(data.sessions());
            if(relevant.isEmpty())return answer("本轮没有查到符合所选日期、医生或时段的演示场次，未生成草稿。");
            var chosen=input.selected(data.sessions());
            if(created!=null) {
                var draft=JSON.readTree(created.text());
                if(!"PENDING_CONFIRMATION".equals(draft.path("status").asText()))
                    return answer("本次未取得待确认草稿，请重新核对选择和场次状态；尚未完成预约。");
                var saved=draft.path("draft");var slot=saved.path("session");
                if(slot.isMissingNode()||slot.isNull())return answer("草稿返回的信息不完整，请在网页核对详情，尚未确认预约。");
                String text="已生成虚构演示预约草稿："+saved.path("visitDate").asText()+"（Asia/Shanghai），"
                        +slot.path("doctorName").asText()+"，"+shortTime(slot.path("startTime").asText())+"至"+shortTime(slot.path("endTime").asText())
                        +"。草稿不占号，尚未预约，请在网页核对后点击确认。";
                if(chosen!=null&&"NOT_RELEASED".equals(chosen.bookingStatus()))
                    text+="查询时尚未放号，放号时间为"+chosen.releaseAt()+"，需等到放号后才能确认。";
                return answer(text);
            }
            if(!input.draft())return answer(summary(relevant));
            if(chosen==null)return answer(summary(relevant)+"请明确所选日期、医生和时段后再准备草稿。");
            if(!data.bookingEnabled()||!Set.of("AVAILABLE","NOT_RELEASED").contains(chosen.bookingStatus())||chosen.referenceRemaining()<=0)
                return answer(chosen.referenceRemaining()<=0&&!"NO_SCHEDULE".equals(chosen.bookingStatus())
                        ?"该演示场次当前已满，没有可用余量，未生成草稿。"
                        :"该演示场次暂不具备办理条件，未生成草稿。");
            String receipt=result.path("queryReceipt").asText("");
            if(receipt.isBlank())return answer("本轮查询校验不完整，未生成草稿，请重新查询。");
            return call(request,CREATE,Map.of("hospitalId",input.hospital(),"department",input.department(),
                    "visitDate",chosen.visitDate().toString(),"sessionId",chosen.sessionId(),"queryReceipt",receipt));
        } catch(Exception invalid) {return answer("本轮排班结果无法核实，未继续生成草稿，请稍后重试。");}
    }
    private static String shortTime(String value){return BookingTurn.shortTime(value);}
    private static String summary(List<SessionItem> items) {
        StringBuilder text=new StringBuilder("以下为虚构演示排班（Asia/Shanghai）：");
        for(var s:items.stream().limit(4).toList()) {
            text.append(s.visitDate()).append("，").append(s.doctorName()).append("，")
                    .append(shortTime(s.startTime())).append("至").append(shortTime(s.endTime())).append("，");
            if("NO_SCHEDULE".equals(s.bookingStatus()))text.append("缺少当天总排班");
            else if(s.referenceRemaining()<=0)text.append("已满，无可用余量");
            else if("NOT_RELEASED".equals(s.bookingStatus()))text.append("尚未放号，参考余量").append(s.referenceRemaining())
                    .append("，放号时间").append(s.releaseAt());
            else if("AVAILABLE".equals(s.bookingStatus()))text.append("查询时可申请，参考余量").append(s.referenceRemaining());
            else text.append("办理状态待核实");
            text.append("。");
        }
        if(items.size()>4)text.append("另有场次，请明确日期或医生以缩小范围。");
        return text.append("查询不预留名额。").toString();
    }
    private static ChatResponse answer(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).tokenUsage(new TokenUsage(0,0))
                .finishReason(FinishReason.STOP).build();
    }
    private static ChatResponse call(ChatRequest request,String name,Map<String,String> args) {
        if(request.toolSpecifications()==null||request.toolSpecifications().stream().noneMatch(t->name.equals(t.name())))
            return answer("当前预约查询或草稿功能不可用，请稍后重试。");
        try {
            return ChatResponse.builder().aiMessage(AiMessage.from(ToolExecutionRequest.builder()
                    .id(UUID.randomUUID().toString()).name(name).arguments(JSON.writeValueAsString(args)).build()))
                    .tokenUsage(new TokenUsage(0,0)).finishReason(FinishReason.TOOL_EXECUTION).build();
        }catch(Exception error){throw new IllegalStateException("Could not encode booking request",error);}
    }
    public static ChatLanguageModel sync(ChatLanguageModel delegate,AppointmentQueryContext context) {
        return new ChatLanguageModel() {
            public ChatRequestParameters defaultRequestParameters(){return delegate.defaultRequestParameters();}
            public Set<Capability> supportedCapabilities(){return delegate.supportedCapabilities();}
            public ChatResponse chat(ChatRequest request) {
                var response=decide(request,context);
                return response==null?delegate.chat(request):response;
            }
        };
    }
    public static StreamingChatLanguageModel streaming(StreamingChatLanguageModel delegate,AppointmentQueryContext context) {
        return new StreamingChatLanguageModel() {
            public ChatRequestParameters defaultRequestParameters(){return delegate.defaultRequestParameters();}
            public Set<Capability> supportedCapabilities(){return delegate.supportedCapabilities();}
            public void chat(ChatRequest request,StreamingChatResponseHandler handler) {
                var response=decide(request,context);
                if(response==null){delegate.chat(request,handler);return;}
                if(response.aiMessage().text()!=null)handler.onPartialResponse(response.aiMessage().text());
                handler.onCompleteResponse(response);
            }
        };
    }
}
