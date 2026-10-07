package com.ruomu.xiaozhi.context;
import dev.langchain4j.data.message.*;
import java.util.List;
/** Transparent, bounded query expansion; does not turn history into appointment authorization. */
public final class FollowUpQuery {
    public static String rewrite(String query,List<ChatMessage> memory){
        if(query.length()>40 || !query.matches(".*(那里|它|这个|上述|那个|该服务台|还需要|怎么归还).*"))return query;
        if(memory==null)return query;
        for(int i=memory.size()-1;i>=0;i--)if(memory.get(i) instanceof UserMessage u){
            String previous=u.singleText();
            String start="【用户本轮原始消息】\n", end="\n【用户本轮原始消息结束】";
            int a=previous.indexOf(start),b=a<0?-1:previous.indexOf(end,a+start.length());
            if(a>=0 && b>=0)previous=previous.substring(a+start.length(),b);
            else if(previous.contains("【本轮知识检索参考数据】"))continue;
            if(previous.equals(query))continue;
            if(previous.length()<=160)return previous+"\n追问："+query;
        }
        return query;
    }
}
