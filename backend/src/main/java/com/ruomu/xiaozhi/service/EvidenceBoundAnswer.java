package com.ruomu.xiaozhi.service;

import com.ruomu.xiaozhi.dto.KnowledgeSearchResponse.Match;
import java.util.*;

/** Extractive answer contract. Proves quotation provenance, not relevance or completeness. */
public final class EvidenceBoundAnswer {
    private EvidenceBoundAnswer(){}
    public static String render(List<Match> quotations,List<Match> retrieved) {
        if(quotations==null||quotations.isEmpty()||quotations.size()>2)throw new IllegalArgumentException("Missing evidence");
        var seen=new HashSet<Integer>();var out=new StringBuilder("以下为虚构教学资料中的原文，不能用于判断个人预约是否成功：\n");
        for(var quote:quotations) {
            if(quote==null||quote.text()==null||quote.text().isBlank()||quote.text().length()>4000||!seen.add(quote.index()))
                throw new IllegalArgumentException("Invalid quotation");
            boolean found=retrieved.stream().anyMatch(r->r.index()==quote.index()&&Objects.equals(r.source(),quote.source())
                    &&r.text()!=null&&r.text().contains(quote.text()));
            if(!found)throw new IllegalArgumentException("Unsupported quotation");
            out.append("来源：").append(quote.source()).append("，片段 ").append(quote.index()).append("\n")
                    .append("引用：").append(quote.text()).append("\n");
        }
        return out.append("以上仅为资料摘录；个人预约状态以当前账户的预约记录为准。").toString();
    }
}
