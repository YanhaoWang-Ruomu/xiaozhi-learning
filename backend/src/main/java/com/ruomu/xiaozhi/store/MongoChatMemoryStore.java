package com.ruomu.xiaozhi.store;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.ReplaceOptions;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.mongodb.client.model.Filters.eq;

@Component
public class MongoChatMemoryStore implements ChatMemoryStore {

    private final MongoCollection<Document> collection;

    public MongoChatMemoryStore(MongoTemplate mongoTemplate) {
        this.collection = mongoTemplate.getCollection("chat_memory");
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        Document document = collection
                .find(eq("_id", memoryId.toString()))
                .first();

        if (document == null) {
            return List.of();
        }

        return ChatMessageDeserializer.messagesFromJson(
                document.getString("messages")
        );
    }

    @Override
    public void updateMessages(
            Object memoryId,
            List<ChatMessage> messages) {

        String id = memoryId.toString();
        String messagesJson =
                ChatMessageSerializer.messagesToJson(messages);

        Document document = new Document("_id", id)
                .append("messages", messagesJson);

        collection.replaceOne(
                eq("_id", id),
                document,
                new ReplaceOptions().upsert(true)
        );
    }

    @Override
    public void deleteMessages(Object memoryId) {
        collection.deleteOne(eq("_id", memoryId.toString()));
    }
}