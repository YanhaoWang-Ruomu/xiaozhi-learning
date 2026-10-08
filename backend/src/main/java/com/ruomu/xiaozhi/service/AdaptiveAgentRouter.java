package com.ruomu.xiaozhi.service;

import java.util.List;

/** Bounded server routing, not an autonomous planner. Raw current-turn input only. */
public final class AdaptiveAgentRouter {
    public enum Route { DIRECT, KNOWLEDGE, BOOKING, CLARIFY, GENERAL }
    public record Decision(Route route,String reason,List<String> steps,String reply) {
        public boolean retrieve(){return route==Route.KNOWLEDGE||route==Route.GENERAL;}
    }
    private AdaptiveAgentRouter(){}
    public static Decision decide(String raw,BookingTurn booking) {
        String q=raw==null?"":raw.strip();
        if(q.isBlank())return new Decision(Route.CLARIFY,"EMPTY",List.of("clarify"),"请说明想了解的事项。");
        if(q.matches("(?s).*(疼|痛|呼吸|昏|晕|出血|自杀|自残|症状|中毒|休克|药|病情).*"))
            return new Decision(Route.GENERAL,"MEDICAL_OR_MIXED",List.of("existing_medical_prompt"),"");
        if(booking!=null&&booking.handled()){
            boolean ambiguous=booking.dateConflict()||booking.uncertain()||booking.hospital()==null||booking.department()==null;
            return new Decision(ambiguous?Route.CLARIFY:Route.BOOKING,ambiguous?"BOOKING_NEEDS_DETAIL":"VERIFIED_BOOKING",
                    ambiguous?List.of("clarify"):booking.draft()?List.of("query_sessions","validate_selection","prepare_draft","human_confirmation"):List.of("query_sessions","answer_from_result"),"");
        }
        if(q.matches("(你好|您好|嗨|hello|hi|谢谢|谢谢你|再见)[！!。.\\s]*"))
            return new Decision(Route.DIRECT,"EXACT_GREETING",List.of("direct"),"你好，我是小智，可以介绍演示就医资料、查询演示排班，并协助准备需要人工确认的预约草稿。");
        if(q.matches("(这个|那个|怎么办|在哪里|多少钱|什么时候)[？?。\\s]*"))
            return new Decision(Route.CLARIFY,"NO_REFERENT",List.of("clarify"),"请说明具体医院、服务或要查询的事项。");
        if(q.matches("(?s).*(医院|服务台|导诊|候诊|就诊|挂号|预约|轮椅|停车|住院|门诊|证件|材料|归还).*"))
            return new Decision(Route.KNOWLEDGE,"HOSPITAL_INFORMATION",List.of("retrieve","check_evidence","answer_or_clarify"),"");
        return new Decision(Route.GENERAL,"UNCLASSIFIED",List.of("existing_retrieval","existing_model"),"");
    }
}
