package com.ruomu.xiaozhi.service;

import java.util.*;
import java.util.regex.Pattern;

/** Narrow regression flags, not an entailment model or a medical correctness judge. */
final class AgentAnswerChecks {
    private static final Pattern INTERNAL=Pattern.compile(
        "(?i)queryReceipt|sessionId|bookingEnabled|bookable|referenceRemaining|"
        +"queryAppointment(?:Sessions|Rule|Schedules)|createAppointmentDraft|"
        +"PENDING_CONFIRMATION|NOT_RELEASED|QUERY_FAILED");
    private static final Pattern MARKDOWN=Pattern.compile("(?m)^\\s{0,3}#{1,6}\\s|\\*\\*|```|\\|[^\\n]+\\|");
    static List<String> surface(String answer) {
        if(answer==null||answer.isBlank())return List.of("EMPTY_ANSWER");
        var failures=new ArrayList<String>();
        if(INTERNAL.matcher(answer).find() || answer.matches("(?s).*场次编号.{0,8}[0-9]+.*"))failures.add("INTERNAL_IMPLEMENTATION_EXPOSED");
        if(MARKDOWN.matcher(answer).find())failures.add("MARKDOWN_FORMAT");
        if(answer.codePointCount(0,answer.length())>400)failures.add("OVERLONG_ANSWER");
        return failures;
    }
    static List<String> review(String scenario,int turn,String answer) {
        var failures=new ArrayList<>(surface(answer));
        if(answer==null||answer.isBlank())return failures;
        if(answer.matches("(?s).*聊天.{0,12}(?:不会|不|不能).{0,8}(?:任何系统|任何操作|任何动作).*"))
            failures.add("OVERBROAD_CHAT_LIMITATION");
        if(answer.matches("(?s).*(?:未放号|尚未放号).{0,16}(?:不能|不允许|无法).{0,8}(?:创建|生成|准备)草稿.*"))
            failures.add("WRONG_UNRELEASED_DRAFT_RULE");
        if("capacity-changed".equals(scenario)&&turn==2) {
            if(!answer.matches("(?s).*(?:已满|满额|没有.*余量|无.*余量|余量.{0,3}0).*"))
                failures.add("FULL_RESULT_NOT_EXPLAINED");
            if(answer.matches("(?s).*(?:建议|可以考虑|可改选|不妨).{0,20}下午.*"))
                failures.add("FULL_ALTERNATIVE_SUGGESTED");
        }
        if("tool-failure".equals(scenario)&&
                !answer.matches("(?s).*(?:失败|暂时不可用|无法核实|无法查询).*"))
            failures.add("QUERY_FAILURE_NOT_EXPLAINED");
        return failures.stream().distinct().toList();
    }
}
