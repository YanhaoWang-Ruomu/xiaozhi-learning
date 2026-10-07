package com.ruomu.xiaozhi.context;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.data.message.*;
import java.util.*;
import java.nio.charset.StandardCharsets;

/** Conservative UTF-8 byte budget, NOT an exact Qwen tokenizer. Evicts complete old turns. */
public final class BudgetChatMemory implements ChatMemory {
    private final Object id;
    private final ChatMemoryStore store;
    private final int budget;
    public BudgetChatMemory(Object id, ChatMemoryStore store,int budget){
        if(budget<1000)throw new IllegalArgumentException("Memory budget too small");
        this.id=id;this.store=store;this.budget=budget;
    }
    public Object id(){return id;}
    public synchronized void add(ChatMessage message){
        var all=new ArrayList<>(store.getMessages(id));
        if(message instanceof SystemMessage)all.removeIf(m->m instanceof SystemMessage);
        if(message instanceof SystemMessage)all.add(0,message);else all.add(message);
        trim(all,budget);store.updateMessages(id,List.copyOf(all));
    }
    public synchronized List<ChatMessage> messages(){var all=new ArrayList<>(store.getMessages(id));trim(all,budget);return List.copyOf(all);}
    public void clear(){store.deleteMessages(id);}
    public static int estimate(List<ChatMessage> messages){return ChatMessageSerializer.messagesToJson(messages).getBytes(StandardCharsets.UTF_8).length;}
    static void trim(List<ChatMessage> all,int budget){
        while(estimate(all)>budget || all.size()>20){
            int first=-1,next=-1;
            for(int i=0;i<all.size();i++)if(all.get(i) instanceof UserMessage){if(first<0)first=i;else{next=i;break;}}
            if(next<0){if(estimate(all)>budget)throw new IllegalArgumentException("本轮上下文超过预算，请缩短问题或新建会话");break;}
            all.subList(first,next).clear();
        }
        // Old pre-budget memories may start with an orphan assistant/tool result.
        int start=!all.isEmpty() && all.get(0) instanceof SystemMessage ? 1:0;
        while(start<all.size() && !(all.get(start) instanceof UserMessage))all.remove(start);
    }
}
