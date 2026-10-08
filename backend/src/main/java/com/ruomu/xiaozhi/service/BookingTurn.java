package com.ruomu.xiaozhi.service;

import com.ruomu.xiaozhi.dto.AppointmentSessionResponse.SessionItem;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

/** Deliberately bounded demo vocabulary. Unresolved/negated choices cannot authorize a draft. */
public record BookingTurn(String text, String hospital, String department, LocalDate date,
                          boolean dateConflict, boolean handled, boolean draft, boolean control,
                          boolean uncertain, String previousSelectionText) {
    static BookingTurn parse(String raw, BookingTurn previous, LocalDate today) {
        String text=raw==null?"":raw.strip();
        boolean medical=text.matches("(?s).*(症状|病情|疼|胸痛|呼吸|昏|晕|不舒服|急救|自杀|自残|出血|中毒|休克).*");
        boolean quoted=text.length()>1000 || text.matches("(?s).*(假设|假装|举例|示例|如果|忽略|系统指令|角色扮演|【|\\x60|[\"“]).*");
        String action=text.replace("待确认","");
        boolean control=action.matches("(?s).*(确认|扣号|取消).*");
        boolean appointment=text.matches("(?s).*(预约|草稿|排班|场次|时段|余量|号源|哪些医生).*");
        boolean handled=!medical&&!quoted&&appointment&&!text.contains("没有预约需求")
                && !text.matches("(?s).*(规则|流程|提前几天).*");
        if(control&&!medical&&!quoted&&(appointment||action.contains("扣号")||(previous!=null&&previous.handled())))handled=true;
        boolean draft=handled&&!control&&text.matches("(?s).*(预约|(?:准备|生成|创建).*草稿).*")
                &&!text.matches("(?s).*(只查询|仅查询|先不|暂不|不要|不需要|别).{0,12}(?:准备|创建|生成|草稿).*")
                &&!text.matches("(?s).*(只查询|仅查询).*");
        boolean uncertain=uncertainChoice(text);
        if(previous!=null&&(!previous.handled()||previous.dateConflict()||uncertainChoice(previous.text())))previous=null;
        boolean continuation=text.matches("(?s).*(同一|刚才|改成|改为|这个|那个|该场次|仍).*");
        Set<String> hospitals=new LinkedHashSet<>();
        var hm=Pattern.compile("(?i)(?<![a-z0-9_])(?:DEMO[0-9]{1,6}|UNKNOWN)(?![a-z0-9_])").matcher(text);
        while(hm.find())hospitals.add(hm.group().toUpperCase(Locale.ROOT));
        String hospital=hospitals.size()==1?hospitals.iterator().next():null;
        if(hospitals.isEmpty()&&previous!=null&&continuation&&!text.contains("医院"))hospital=previous.hospital();
        String department=text.contains("内科")?"内科":null;
        if(department==null&&previous!=null&&continuation&&!text.contains("科"))department=previous.department();
        Set<LocalDate> dates=new LinkedHashSet<>();
        boolean badDate=false;
        var dm=Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}|大后天|后天|明天|今天").matcher(text);
        while(dm.find()) {
            String token=dm.group();
            try {dates.add(switch(token){case "今天"->today;case "明天"->today.plusDays(1);
                case "后天"->today.plusDays(2);case "大后天"->today.plusDays(3);default->LocalDate.parse(token);});}
            catch(RuntimeException e){badDate=true;}
        }
        if(text.matches("(?s).*(周末|下周|过几天).*"))badDate=true;
        LocalDate date=dates.size()==1?dates.iterator().next():null;
        if(dates.isEmpty()&&!badDate&&continuation&&previous!=null)date=previous.date();
        String history=continuation&&previous!=null?previous.text():"";
        return new BookingTurn(text,hospital,department,date,badDate||dates.size()>1||hospitals.size()>1,
                handled,draft,control,uncertain,history);
    }
    public List<SessionItem> relevant(List<SessionItem> sessions) {
        var candidates=sessions.stream().filter(s->date==null||date.equals(s.visitDate())).toList();
        String doctorText=text.matches("(?s).*(同一位|同一名|刚才).*")?text+"\n"+previousSelectionText:text;
        var doctors=candidates.stream().map(SessionItem::doctorName).distinct().filter(doctorText::contains).toList();
        if(doctors.size()==1)candidates=candidates.stream().filter(s->doctors.contains(s.doctorName())).toList();
        boolean am=text.contains("上午"),pm=text.contains("下午");
        if(am!=pm)candidates=candidates.stream().filter(s->s.slotName().contains(am?"上午":"下午")).toList();
        return candidates;
    }
    public SessionItem selected(List<SessionItem> sessions) {
        if(!handled||!draft||control||uncertain||dateConflict||date==null||hospital==null||department==null)return null;
        String doctorText=text.matches("(?s).*(同一位|同一名|刚才).*")?text+"\n"+previousSelectionText:text;
        var names=sessions.stream().map(SessionItem::doctorName).distinct().filter(doctorText::contains).toList();
        if(names.size()!=1 || (text.contains("上午")&&text.contains("下午")))return null;
        var times=new ArrayList<String>();
        var tm=Pattern.compile("[0-9]{1,2}:[0-9]{2}").matcher(text);
        while(tm.find())times.add(tm.group());
        if(!times.isEmpty()&&times.size()!=2)return null;
        var selected=sessions.stream().filter(s->date.equals(s.visitDate())&&names.get(0).equals(s.doctorName()))
                .filter(s->!text.contains("上午")||s.slotName().contains("上午"))
                .filter(s->!text.contains("下午")||s.slotName().contains("下午"))
                .filter(s->times.isEmpty()
                        ? text.contains("上午")||text.contains("下午")
                        : times.equals(List.of(shortTime(s.startTime()),shortTime(s.endTime())))).toList();
        return selected.size()==1?selected.get(0):null;
    }
    private static boolean uncertainChoice(String text) {
        String choices=text.replaceAll("(?:先|暂时|暂)?(?:不要|不需要|不|别)(?:准备|生成|创建)草稿","");
        return choices.matches("(?s).*(不|没|未选|都行|随便|替我选|别|假设|假装|忽略|指令|或者|或是|也许|可能).*");
    }
    static String shortTime(String time){return time!=null&&time.length()>=5?time.substring(0,5):String.valueOf(time);}
}
