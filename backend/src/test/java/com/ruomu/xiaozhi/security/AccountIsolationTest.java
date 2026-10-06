package com.ruomu.xiaozhi.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.ruomu.xiaozhi.controller.*;
import com.ruomu.xiaozhi.service.*;
import jakarta.servlet.Filter;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.Executor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 显式启用，使用本机Mongo随机临时库；不调用模型、不连接业务MySQL。 */
@SpringJUnitConfig(AccountIsolationTest.Config.class)
@WebAppConfiguration
@EnabledIfSystemProperty(named="xiaozhi.auth.integration", matches="true")
class AccountIsolationTest {
    @Configuration @EnableWebMvc @EnableWebSecurity
    @Import({AccountSecurityConfig.class, AccountService.class, AuthController.class,
        ConversationHistoryService.class, ConversationController.class, OwnedAppointmentService.class,
        AppointmentController.class, ChatController.class})
    static class Config {
        @Bean(destroyMethod="close") MongoClient mongoClient() { return MongoClients.create("mongodb://127.0.0.1:27017/?serverSelectionTimeoutMS=5000"); }
        @Bean MongoTemplate mongo(MongoClient client) { return new MongoTemplate(client, "xiaozhi_auth_test_" + UUID.randomUUID().toString().replace("-", "")); }
        @Bean AppointmentDraftService drafts() { return mock(AppointmentDraftService.class); }
        @Bean AppointmentService appointments() { return mock(AppointmentService.class); }
        @Bean ChatAssistant assistant() { return mock(ChatAssistant.class); }
        @Bean ObjectMapper mapper() { return new ObjectMapper(); }
        @Bean(name="chatStreamExecutor") Executor executor() { return Runnable::run; }
    }
    @Autowired WebApplicationContext context;
    @Autowired MongoTemplate mongo;
    @Autowired AppointmentDraftService drafts;
    @Autowired AppointmentService appointments;
    @Autowired ChatAssistant assistant;
    @Autowired AccountService accounts;
    @Autowired ConversationHistoryService history;
    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;
    private record Client(MockHttpSession session, String header, String token) {}
    private Client csrf(MockHttpSession session) throws Exception {
        var request = get("/api/auth/csrf");
        if (session != null) request.session(session);
        var result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        JsonNode value = json.readTree(result.getResponse().getContentAsString());
        return new Client((MockHttpSession)result.getRequest().getSession(), value.get("headerName").asText(), value.get("token").asText());
    }
    private MockHttpServletRequestBuilder write(String url, Client c, Object body) throws Exception {
        return post(url).session(c.session()).header(c.header(), c.token()).contentType("application/json").content(json.writeValueAsString(body));
    }
    private Client account(String name) throws Exception {
        Client c = csrf(null);
        Map<String,String> credentials = Map.of("username",name,"password","demo-test-password-2026");
        mvc.perform(write("/api/auth/register", c, credentials)).andExpect(status().isCreated());
        String originalId = c.session().getId();
        mvc.perform(write("/api/auth/login", c, credentials)).andExpect(status().isOk()).andExpect(jsonPath("passwordHash").doesNotExist());
        assertNotEquals(originalId, c.session().getId(), "rotate session ID on login");
        Client logged = csrf(c.session());
        mvc.perform(post("/api/conversations").session(logged.session()).header(c.header(), c.token()).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        return logged;
    }
    @Test void accountSessionCsrfAndObjectOwnership() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain",Filter.class)).build();
        try {
            mvc.perform(get("/api/conversations")).andExpect(status().isUnauthorized());
            mvc.perform(post("/api/auth/register").contentType("application/json").content("{}")).andExpect(status().isForbidden());
            Client a = account("test_user_a"), b = account("test_user_b");
            String aId = accounts.loadUserByUsername("test_user_a").userId();
            String bId = accounts.loadUserByUsername("test_user_b").userId();
            assertTrue(accounts.loadUserByUsername("test_user_a").passwordHash().startsWith("$2"));
            mvc.perform(write("/api/auth/register", a, Map.of("username","TEST_USER_A","password","demo-test-password-2026"))).andExpect(status().isConflict());
            String conversation = UUID.randomUUID().toString();
            mvc.perform(write("/api/conversations", a, Map.of("conversationId",conversation))).andExpect(status().isOk());
            mvc.perform(get("/api/conversations/"+conversation+"/messages").session(a.session())).andExpect(status().isOk());
            mvc.perform(get("/api/conversations/"+conversation+"/messages").session(b.session())).andExpect(status().isNotFound());
            mvc.perform(get("/api/conversations").session(b.session())).andExpect(jsonPath("$.items.length()").value(0));
            mvc.perform(write("/api/conversations", b, Map.of("conversationId",conversation))).andExpect(status().isNotFound());
            for (String endpoint : List.of("/api/chat", "/api/chat/stream"))
                mvc.perform(write(endpoint, b, Map.of("conversationId",conversation,"message","hello"))).andExpect(status().isNotFound());
            verifyNoInteractions(assistant);
            String draft="DRAFT-"+UUID.randomUUID(), appointment="DEMO-"+UUID.randomUUID();
            mongo.getCollection("demo_appointment_drafts").insertOne(new Document("_id",draft).append("conversationId",conversation).append("appointmentId",appointment));
            for (String url : List.of("/api/appointments/drafts/"+draft,"/api/appointments/"+appointment,"/api/appointments/drafts?conversationId="+conversation))
                mvc.perform(get(url).session(b.session())).andExpect(status().isNotFound());
            for (String url : List.of("/api/appointments/drafts/"+draft+"/confirm","/api/appointments/drafts/"+draft+"/cancel","/api/appointments/"+appointment+"/cancel"))
                mvc.perform(write(url,b,Map.of("confirmed",true))).andExpect(status().isNotFound());
            mvc.perform(write("/api/appointments/drafts?conversationId="+conversation,b,Map.of("hospitalId","DEMO001","department","内科","visitDate","2026-10-07"))).andExpect(status().isNotFound());
            verifyNoInteractions(drafts,appointments);
            mvc.perform(get("/api/appointments/drafts/"+draft).session(a.session())).andExpect(status().isOk());
            verify(drafts).findById(draft);
            // 异步工具沿已验证的会话归属创建草稿，不依赖线程中的登录信息。
            context.getBean(OwnedAppointmentService.class).createFromChat(null, conversation);
            verify(drafts).createDraft(null, conversation);
            String legacy="legacy-"+UUID.randomUUID(), key="a".repeat(64);
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
            mongo.getCollection("chat_conversations").insertOne(new Document("_id",legacy).append("owner",hash).append("title","legacy").append("createdKey",new ObjectId()).append("createdAt",new Date()).append("updatedAt",new Date()));
            mvc.perform(write("/api/conversations/import-browser",a,Map.of("legacyKey",key))).andExpect(jsonPath("importedCount").value(1));
            mvc.perform(write("/api/conversations/import-browser",b,Map.of("legacyKey",key))).andExpect(jsonPath("importedCount").value(0));
            mvc.perform(get("/api/conversations/"+legacy+"/messages").session(b.session())).andExpect(status().isNotFound());
            String orphan=UUID.randomUUID().toString();
            mongo.getCollection("chat_memory").insertOne(new Document("_id",orphan));
            mvc.perform(write("/api/conversations",a,Map.of("conversationId",orphan))).andExpect(status().isConflict());
            mvc.perform(write("/api/knowledge/pinecone/sync",a,Map.of())).andExpect(status().isForbidden());
            mvc.perform(get("/api/chat").session(a.session())).andExpect(status().isForbidden());
            mvc.perform(write("/api/auth/logout",a,Map.of())).andExpect(status().isOk());
            assertTrue(a.session().isInvalid());
            mvc.perform(get("/api/conversations")).andExpect(status().isUnauthorized());
            assertEquals(bId, history.ownerAccount(history.create(null,bId).conversationId()));
        } finally { mongo.getDb().drop(); }
    }
}
