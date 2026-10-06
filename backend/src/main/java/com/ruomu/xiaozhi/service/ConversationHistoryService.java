package com.ruomu.xiaozhi.service;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.UpdateOptions;
import com.ruomu.xiaozhi.dto.ChatResponse;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Sorts.descending;
import static com.mongodb.client.model.Updates.*;

/** 完整展示历史，与 LangChain4j chat_memory 的有限记忆窗口相互独立。当前为单实例本地演示。 */
@Service
public class ConversationHistoryService {
    private final MongoCollection<Document> conversations;
    private final MongoCollection<Document> turns;
    private final Set<ObjectId> activeTurns = ConcurrentHashMap.newKeySet();

    public ConversationHistoryService(MongoTemplate mongo) {
        conversations = mongo.getCollection("chat_conversations");
        turns = mongo.getCollection("chat_history_turns");
        conversations.createIndex(Indexes.descending("createdKey"));
        turns.createIndex(Indexes.compoundIndex(Indexes.ascending("conversationId"), Indexes.descending("_id")));
    }

    public Conversation create(String requestedId, String accessKey) {
        String owner = owner(accessKey);
        String id = requestedId == null ? UUID.randomUUID().toString() : requireId(requestedId);
        Date now = new Date();
        try {
            conversations.updateOne(eq("_id", id), combine(
                    setOnInsert("owner", owner), setOnInsert("createdKey", new ObjectId()), setOnInsert("title", "新会话"),
                    setOnInsert("createdAt", now), setOnInsert("updatedAt", now)), new UpdateOptions().upsert(true));
        } catch (MongoWriteException e) {
            if (e.getError().getCode() != 11000) throw e;
        }
        return conversation(owned(id, owner));
    }

    public ConversationPage list(String before, int limit, String accessKey) {
        String owner = owner(accessKey);
        int size = pageSize(limit);
        var query = before == null ? eq("owner", owner) : and(eq("owner", owner), lt("createdKey", cursor(before)));
        List<Document> rows = conversations.find(query).sort(descending("createdKey")).limit(size + 1).into(new ArrayList<>());
        boolean more = rows.size() > size;
        if (more) rows.remove(size);
        return new ConversationPage(rows.stream().map(this::conversation).toList(),
                more ? rows.get(rows.size() - 1).getObjectId("createdKey").toHexString() : null);
    }

    public HistoryPage history(String rawId, String before, int limit, String accessKey) {
        String id = requireId(rawId);
        Document meta = owned(id, owner(accessKey));
        var filter = before == null ? eq("conversationId", id) : and(eq("conversationId", id), lt("_id", cursor(before)));
        List<Document> rows = turns.find(filter).sort(descending("_id")).limit(pageSize(limit) + 1).into(new ArrayList<>());
        boolean more = rows.size() > pageSize(limit);
        if (more) rows.remove(rows.size() - 1);
        String next = more ? rows.get(rows.size() - 1).getObjectId("_id").toHexString() : null;
        Collections.reverse(rows);
        List<Message> messages = new ArrayList<>();
        for (Document row : rows) {
            ObjectId key = row.getObjectId("_id");
            String state = row.getString("state");
            // 后端重启后未完成的请求不会自动重放，也不会误报完成。
            if ("processing".equals(state) && !activeTurns.contains(key)) state = "interrupted";
            messages.add(new Message(key.toHexString() + "-user", "user", row.getString("userText"), "complete", List.of(), time(row, "createdAt")));
            List<ChatResponse.Source> sources = row.getList("sources", Document.class, List.of()).stream()
                    .map(s -> new ChatResponse.Source(s.getString("source"), s.getInteger("index"), s.getString("text"))).toList();
            String reply = row.getString("reply");
            messages.add(new Message(key.toHexString() + "-assistant", "assistant", reply == null ? "" : reply,
                    state, sources, time(row, "updatedAt")));
        }
        boolean processing = turns.find(and(eq("conversationId", id), eq("state", "processing")))
                .projection(new Document("_id", 1)).into(new ArrayList<>()).stream()
                .anyMatch(row -> activeTurns.contains(row.getObjectId("_id")));
        return new HistoryPage(conversation(meta), List.copyOf(messages), next, processing);
    }

    public ObjectId begin(String rawId, String message, String accessKey) {
        String id = requireId(rawId);
        // 旧演示页面不带密钥时仅允许访问尚未归属的新会话，不写展示历史。
        if (accessKey == null) {
            if (conversations.find(eq("_id", id)).first() != null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "此会话需要访问密钥");
            return null;
        }
        create(id, accessKey);
        ObjectId key = new ObjectId();
        Date now = new Date();
        activeTurns.add(key);
        try {
            turns.insertOne(new Document("_id", key).append("conversationId", id).append("userText", message)
                    .append("reply", "").append("state", "processing").append("sources", List.of())
                    .append("createdAt", now).append("updatedAt", now));
            String title = message.codePoints().limit(28).collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
            conversations.updateOne(and(eq("_id", id), eq("title", "新会话")), set("title", title));
            conversations.updateOne(eq("_id", id), set("updatedAt", now));
            return key;
        } catch (RuntimeException e) {
            activeTurns.remove(key);
            throw e;
        }
    }

    public void complete(ObjectId key, ChatResponse response) {
        if (key == null) return;
        if (response.reply() == null || response.reply().isBlank()) throw new IllegalStateException("历史回复不能为空");
        List<Document> sources = response.sources().stream().map(s -> new Document("source", s.source())
                .append("index", s.index()).append("text", s.text())).toList();
        var result = turns.updateOne(and(eq("_id", key), eq("state", "processing")), combine(
                set("reply", response.reply()), set("sources", sources), set("state", "complete"), set("updatedAt", new Date())));
        if (result.getMatchedCount() != 1) throw new IllegalStateException("历史请求已结束或不存在");
        activeTurns.remove(key);
    }

    public void interrupted(ObjectId key) {
        if (key == null) return;
        try {
            turns.updateOne(and(eq("_id", key), eq("state", "processing")), combine(set("state", "interrupted"), set("updatedAt", new Date())));
        } finally { activeTurns.remove(key); }
    }

    private String owner(String key) {
        if (key == null || !key.matches("[0-9a-f]{64}")) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "需要有效的浏览器访问密钥");
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private Document owned(String id, String owner) {
        Document row = conversations.find(and(eq("_id", id), eq("owner", owner))).first();
        if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在或不属于当前浏览器");
        return row;
    }

    private Conversation conversation(Document row) {
        return new Conversation(row.getString("_id"), row.getString("title"), time(row, "createdAt"), time(row, "updatedAt"));
    }
    private String time(Document row, String key) { return row.getDate(key).toInstant().toString(); }
    private String requireId(String id) {
        if (id == null || id.isBlank() || id.length() > 128) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "会话编号无效");
        return id.strip();
    }
    private int pageSize(int limit) {
        if (limit < 1 || limit > 50) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "每页数量须为1到50");
        return limit;
    }
    private ObjectId cursor(String value) {
        if (!ObjectId.isValid(value)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分页标记无效");
        return new ObjectId(value);
    }

    public record Conversation(String conversationId, String title, String createdAt, String updatedAt) {}
    public record ConversationPage(List<Conversation> items, String nextCursor) {}
    public record Message(String id, String role, String content, String state, List<ChatResponse.Source> sources, String createdAt) {}
    public record HistoryPage(Conversation conversation, List<Message> messages, String nextCursor, boolean processing) {}
}
