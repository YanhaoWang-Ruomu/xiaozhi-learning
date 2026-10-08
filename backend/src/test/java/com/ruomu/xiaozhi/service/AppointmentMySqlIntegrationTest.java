package com.ruomu.xiaozhi.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.ruomu.xiaozhi.config.AppointmentStorageInitializer;
import com.ruomu.xiaozhi.dto.AppointmentResponse;
import com.ruomu.xiaozhi.dto.CreateAppointmentRequest;
import com.ruomu.xiaozhi.mapper.AppointmentMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Real MySQL/MyBatis/transactions + isolated Mongo drafts. No Spring Boot/cloud model startup. */
@EnabledIfSystemProperty(named = "xiaozhi.mysql.integration", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(60)
class AppointmentMySqlIntegrationTest {
    private static final Instant ACCEPTED = Instant.parse("2030-04-01T02:00:00Z");
    private static final LocalDate VISIT = LocalDate.parse("2030-04-02");
    private final String database = "xiaozhi_booking_test_" + UUID.randomUUID().toString().replace("-", "");
    private JdbcTemplate admin;
    private boolean databaseCreated;
    HikariDataSource source; // Shared only by isolated test/evaluation fixtures.
    JdbcTemplate jdbc; // Shared only by isolated test/evaluation fixtures.
    AppointmentService service; // Shared only by isolated test/evaluation fixtures.
    private MongoClient mongoClient;
    MongoTemplate mongo; // Shared only by isolated test/evaluation fixtures.
    AppointmentDraftService drafts; // Shared only by isolated test/evaluation fixtures.

    @BeforeAll void openIsolatedDatabases() throws Exception {
        // Deliberately fixed non-business port. Do not read SPRING_DATASOURCE_* or application config.
        String url = "jdbc:mysql://127.0.0.1:13307/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8&connectionTimeZone=UTC&connectTimeout=5000&socketTimeout=20000";
        String user = System.getenv().getOrDefault("XIAOZHI_TEST_MYSQL_USER", "root");
        String password = System.getenv().getOrDefault("XIAOZHI_TEST_MYSQL_PASSWORD", "");
        var adminSource = new DriverManagerDataSource(url + options, user, password);
        admin = new JdbcTemplate(adminSource);
        String version = admin.queryForObject("SELECT VERSION()", String.class);
        assertThat(version).startsWith("8.4.");
        assertThat(database).matches("xiaozhi_booking_test_[0-9a-f]{32}");
        admin.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        databaseCreated = true;
        var config = new HikariConfig();
        config.setJdbcUrl(url + database + options);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(12);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(5000);
        source = new HikariDataSource(config);
        jdbc = new JdbcTemplate(source);
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(database);
        // Execute repository schema, not a simplified in-memory replacement. Strip only the known USE.
        for (String file : List.of("002-appointments.sql", "003-appointment-schedules.sql", "004-appointment-attempts.sql",
                "005-appointment-catalog.sql", "006-appointment-sessions.sql", "007-appointment-session-booking.sql")) {
            String sql = new ClassPathResource("booking-schema/" + file).getContentAsString(StandardCharsets.UTF_8);
            sql = sql.replaceAll("(?im)^\\s*USE\\s+xiaozhi_learning\\s*;", "");
            if (java.util.regex.Pattern.compile("(?im)^\\s*(USE|CREATE\\s+DATABASE|DROP\\s+DATABASE)\\b").matcher(sql).find()) {
                throw new IllegalStateException("Schema script attempts to change database scope");
            }
            try (var connection = source.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new EncodedResource(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
            }
        }
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        var mybatis = new MybatisConfiguration();
        mybatis.setMapUnderscoreToCamelCase(true);
        mybatis.addMapper(AppointmentMapper.class);
        factory.setConfiguration(mybatis);
        var mapper = new SqlSessionTemplate(factory.getObject()).getMapper(AppointmentMapper.class);
        // Migration is a separate concern; bypass its hard-coded business-db/import startup only.
        service = new AppointmentService(mapper, mock(AppointmentStorageInitializer.class), source);
        mongoClient = MongoClients.create("mongodb://127.0.0.1:27017/?serverSelectionTimeoutMS=5000");
        mongo = new MongoTemplate(mongoClient, database);
        mongo.getDb().runCommand(new Document("ping", 1));
        drafts = new AppointmentDraftService(mongo, service);
        System.out.println("MYSQL_TEST_ISOLATION_OK version=" + version + " database=" + database + " port=13307");
    }

    @BeforeEach void resetOnlyOurFixture() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(database);
        jdbc.execute("DROP TRIGGER IF EXISTS test_booking_failure");
        for (String table : List.of("demo_appointment_session_bookings", "demo_appointment_request_targets",
                "demo_appointment_attempts", "demo_appointments", "demo_appointment_sessions", "demo_appointment_schedules",
                "demo_appointment_doctors", "demo_appointment_time_slots")) jdbc.update("DELETE FROM " + table);
        mongo.getCollection("demo_appointment_drafts").deleteMany(new Document());
        jdbc.update("INSERT INTO demo_appointment_schedules VALUES ('DEMO001','内科',?,20)", VISIT);
        jdbc.update("INSERT INTO demo_appointment_doctors VALUES ('DEMO001','内科','DOC001','测试医生',1,1)");
        jdbc.update("INSERT INTO demo_appointment_time_slots VALUES ('DEMO001','内科','AM','上午','08:00:00','12:00:00',1,1),('DEMO001','内科','PM','下午','14:00:00','17:00:00',1,2)");
        jdbc.update("INSERT INTO demo_appointment_sessions (session_id,hospital_id,department,visit_date,doctor_id,slot_id,total_capacity) VALUES (1,'DEMO001','内科',?,'DOC001','AM',10),(2,'DEMO001','内科',?,'DOC001','PM',10)", VISIT, VISIT);
    }

    @AfterAll void closeOnlyOurDatabases() {
        try {
            if (mongo != null) mongo.getDb().drop();
        } finally {
            if (mongoClient != null) mongoClient.close();
            if (source != null) source.close();
            if (databaseCreated) admin.execute("DROP DATABASE `" + database + "`");
        }
    }

    private CreateAppointmentRequest request(int session) {
        return new CreateAppointmentRequest("DEMO001", "内科", VISIT, Integer.toString(session));
    }
    private AppointmentResponse book(String draft, int session) {
        return service.createForConfirmedDraft(request(session), draft, ACCEPTED);
    }
    long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    long active() { return jdbc.queryForObject("SELECT COUNT(*) FROM demo_appointments WHERE status='DEMO_CREATED'", Long.class); }
    long active(int session) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM demo_appointment_session_bookings b JOIN demo_appointments a ON a.appointment_id=b.appointment_id WHERE b.session_id=? AND a.status='DEMO_CREATED'", Long.class, session);
    }
    void capacity(int daily, int perSession) {
        jdbc.update("UPDATE demo_appointment_schedules SET total_capacity=?", daily);
        jdbc.update("UPDATE demo_appointment_sessions SET total_capacity=?", perSession);
    }
    private void conflict(Runnable action) {
        assertThat(assertThrows(ResponseStatusException.class, action::run).getStatusCode().value()).isEqualTo(409);
    }
    private void assertNoPartialRows() {
        for (String table : List.of("demo_appointments", "demo_appointment_attempts", "demo_appointment_request_targets", "demo_appointment_session_bookings"))
            assertThat(count(table)).as(table).isZero();
    }
    private <T> List<T> race(int size, IntFunction<T> operation) throws Exception {
        var pool = Executors.newFixedThreadPool(size);
        var barrier = new CyclicBarrier(size);
        try {
            var futures = new ArrayList<Future<T>>();
            for (int i = 0; i < size; i++) {
                int index = i;
                futures.add(pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return operation.apply(index); }));
            }
            List<T> results = new ArrayList<>();
            for (var future : futures) results.add(future.get(30, TimeUnit.SECONDS));
            return results;
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(25, TimeUnit.SECONDS), "Workers must stop before fixture cleanup");
        }
    }
    private boolean tryBook(String key, int session) {
        try { book(key, session); return true; }
        catch (AppointmentRejectedException expected) { return false; } // Infrastructure failures must fail the test.
    }
    private void assertAttemptCounts(int created, int rejected) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM demo_appointment_attempts WHERE status='CREATED'", Long.class)).isEqualTo(created);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM demo_appointment_attempts WHERE status='REJECTED'", Long.class)).isEqualTo(rejected);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM demo_appointment_attempts WHERE status='PROCESSING'", Long.class)).isZero();
    }

    @Test void repeatedConfirmationProducesOneAppointmentAndSnapshot() {
        var first = book("same", 1);
        assertThat(book("same", 1).appointmentId()).isEqualTo(first.appointmentId());
        assertThat(count("demo_appointments")).isEqualTo(1);
        assertThat(count("demo_appointment_session_bookings")).isEqualTo(1);
        assertThat(count("demo_appointment_request_targets")).isEqualTo(1);
        assertAttemptCounts(1, 0);
    }

    @Test void sameKeyCannotChangeSessionOrDate() {
        book("bound", 1);
        conflict(() -> book("bound", 2));
        conflict(() -> service.createForConfirmedDraft(new CreateAppointmentRequest("DEMO001", "内科", VISIT.plusDays(1), "1"), "bound", ACCEPTED));
        assertThat(active()).isEqualTo(1);
    }

    @Test void cancellationRequiresExplicitConfirmation() {
        var appointment = book("explicit", 1);
        assertThat(assertThrows(ResponseStatusException.class, () -> service.cancel(appointment.appointmentId(), false)).getStatusCode().value()).isEqualTo(400);
        assertThat(active()).isEqualTo(1);
        assertThat(service.findById(appointment.appointmentId()).cancelledAt()).isNull();
    }

    @Test void cancellationIsIdempotentReleasesOneSlotAndOldKeyCannotResurrect() {
        capacity(1, 1);
        var appointment = book("original", 1);
        var cancelled = service.cancel(appointment.appointmentId(), true);
        var repeated = service.cancel(appointment.appointmentId(), true);
        assertThat(repeated.status()).isEqualTo("DEMO_CANCELLED");
        assertThat(repeated.cancelledAt()).isNotBlank().isEqualTo(cancelled.cancelledAt());
        assertThat(active()).isZero();
        assertThat(active(1)).isZero();
        assertThat(book("original", 1).status()).isEqualTo("DEMO_CANCELLED");
        assertThat(book("fresh", 1).status()).isEqualTo("DEMO_CREATED");
        assertThrows(AppointmentRejectedException.class, () -> book("overfill", 1));
        assertThat(active()).isEqualTo(1);
        assertThat(count("demo_appointments")).isEqualTo(2); // Cancelled record remains.
    }

    @Test void persistedRejectionCannotBeRetriedIntoSuccessAfterCapacityReturns() {
        capacity(1, 1);
        var first = book("occupy", 1);
        var rejection = assertThrows(AppointmentRejectedException.class, () -> book("rejected", 1));
        service.cancel(first.appointmentId(), true);
        assertThat(assertThrows(AppointmentRejectedException.class, () -> book("rejected", 1)).getReason()).isEqualTo(rejection.getReason());
        conflict(() -> book("rejected", 2));
        assertThat(book("new-key", 1).status()).isEqualTo("DEMO_CREATED");
        assertAttemptCounts(2, 1);
    }

    @Test void concurrentRequestsCannotExceedSessionCapacity() throws Exception {
        capacity(20, 2);
        var results = race(8, i -> tryBook("session-race-" + i, 1));
        assertThat(results.stream().filter(Boolean::booleanValue).count()).isEqualTo(2);
        assertThat(active(1)).isEqualTo(2);
        assertThat(active(2)).isZero();
        assertThat(count("demo_appointment_session_bookings")).isEqualTo(2);
        assertAttemptCounts(2, 6);
    }

    @Test void concurrentDifferentSessionsShareDailyCapacity() throws Exception {
        capacity(3, 10);
        var results = race(8, i -> tryBook("daily-race-" + i, 1 + i % 2));
        assertThat(results.stream().filter(Boolean::booleanValue).count()).isEqualTo(3);
        assertThat(active()).isEqualTo(3);
        assertThat(active(1) + active(2)).isEqualTo(3);
        assertAttemptCounts(3, 5);
    }

    @Test void concurrentSameKeyCreatesExactlyOneAppointment() throws Exception {
        var results = race(8, i -> book("shared-key", 1).appointmentId());
        assertThat(results).containsOnly(results.get(0));
        assertThat(count("demo_appointments")).isEqualTo(1);
        assertThat(count("demo_appointment_session_bookings")).isEqualTo(1);
        assertAttemptCounts(1, 0);
    }

    @Test void concurrentCancellationPreservesOneCancellationTimestamp() throws Exception {
        var first = book("cancel-race", 1);
        var results = race(8, i -> service.cancel(first.appointmentId(), true));
        assertThat(results).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo("DEMO_CANCELLED");
            assertThat(r.cancelledAt()).isNotBlank().isEqualTo(results.get(0).cancelledAt());
        });
        assertThat(active()).isZero();
        assertThat(count("demo_appointments")).isEqualTo(1);
    }

    @Test void snapshotInsertFailureRollsBackAppointmentAttemptAndTarget() {
        jdbc.execute("CREATE TRIGGER test_booking_failure BEFORE INSERT ON demo_appointment_session_bookings FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Injected test failure'");
        try {
            assertThrows(org.springframework.dao.DataAccessException.class, () -> book("rollback", 1));
            assertNoPartialRows();
        } finally { jdbc.execute("DROP TRIGGER test_booking_failure"); }
        assertThat(book("rollback", 1).status()).isEqualTo("DEMO_CREATED");
        assertThat(count("demo_appointments")).isEqualTo(1);
    }

    @Test void staleDoctorSnapshotIsRejectedBeforeBooking() {
        var expected = service.sessionForDraft(request(1));
        jdbc.update("UPDATE demo_appointment_doctors SET doctor_name='已变更医生'");
        assertThrows(AppointmentRejectedException.class, () -> service.createForConfirmedDraft(request(1), "stale", ACCEPTED, expected));
        assertThat(count("demo_appointments")).isZero();
        assertAttemptCounts(0, 1);
    }

    @Test void notYetReleasedValidationRollsBackAttemptAndTarget() {
        var request = new CreateAppointmentRequest("DEMO001", "内科", LocalDate.parse("2030-04-04"), "1");
        conflict(() -> service.createForConfirmedDraft(request, "early", Instant.parse("2030-04-01T01:29:59Z")));
        assertNoPartialRows();
    }

    private String seedDraft(String status) {
        String id = "DRAFT-" + UUID.randomUUID();
        var snapshot = service.sessionForDraft(request(1));
        mongo.getCollection("demo_appointment_drafts").insertOne(new Document("_id", id)
                .append("status", status).append("hospitalId", "DEMO001").append("department", "内科")
                .append("visitDate", VISIT.toString()).append("timeZone", "Asia/Shanghai")
                .append("createdAt", ACCEPTED.toString()).append("confirmationStartedAt", ACCEPTED.toString())
                .append("conversationId", "fixture-conversation").append("sessionId", "1")
                .append("doctorId", snapshot.doctorId()).append("doctorName", snapshot.doctorName())
                .append("slotId", snapshot.slotId()).append("slotName", snapshot.slotName())
                .append("startTime", snapshot.startTime()).append("endTime", snapshot.endTime()));
        return id;
    }

    @Test void confirmedMongoDraftCannotRestoreCancelledMySqlAppointment() {
        String id = seedDraft("CONFIRMING");
        var first = drafts.confirm(id);
        assertThat(drafts.confirm(id).appointmentId()).isEqualTo(first.appointmentId());
        service.cancel(first.appointmentId(), true);
        assertThat(drafts.findById(id).status()).isEqualTo("APPOINTMENT_CANCELLED");
        conflict(() -> drafts.confirm(id));
        assertThat(service.findById(first.appointmentId()).status()).isEqualTo("DEMO_CANCELLED");
        assertThat(active()).isZero();
    }

    @Test void cancelledPendingDraftNeverCreatesMySqlAppointment() {
        String id = seedDraft("PENDING_CONFIRMATION");
        assertThat(drafts.cancel(id).status()).isEqualTo("CANCELLED");
        assertThat(drafts.cancel(id).status()).isEqualTo("CANCELLED");
        conflict(() -> drafts.confirm(id));
        assertNoPartialRows();
    }

    @Test void retryRecoversMySqlSuccessBeforeMongoConfirmationWasSaved() {
        String id = seedDraft("CONFIRMING");
        var committed = book(id, 1); // Simulate committed SQL and a lost response before Mongo update.
        var recovered = drafts.confirm(id);
        assertThat(recovered.appointmentId()).isEqualTo(committed.appointmentId());
        assertThat(drafts.findById(id).status()).isEqualTo("CONFIRMED");
        assertThat(count("demo_appointments")).isEqualTo(1);
    }

    @Test void persistedCapacityRejectionClosesMongoDraft() {
        capacity(0, 0);
        String id = seedDraft("CONFIRMING");
        assertThrows(AppointmentRejectedException.class, () -> drafts.confirm(id));
        assertThat(drafts.findById(id).status()).isEqualTo("CANCELLED");
        assertThat(count("demo_appointments")).isZero();
        assertAttemptCounts(0, 1);
    }

    AppointmentWorkflowService workflow() {
        var history=new ConversationHistoryService(mongo);
        var owned=new OwnedAppointmentService(history,drafts,service,mongo);
        return new AppointmentWorkflowService(mongo,history,drafts,owned,new AppointmentSessionService(source));
    }
    AppointmentWorkflowService.Checkpoint readyWorkflow() {
        var tomorrow=LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(1);
        jdbc.update("UPDATE demo_appointment_schedules SET visit_date=?",tomorrow);
        jdbc.update("UPDATE demo_appointment_sessions SET visit_date=?",tomorrow);
        var history=new ConversationHistoryService(mongo);
        String conversation=UUID.randomUUID().toString();history.create(conversation,"11111111-1111-1111-1111-111111111111");
        var flow=workflow();var cp=flow.create(conversation,"11111111-1111-1111-1111-111111111111");
        return flow.requirements(cp.id(),"11111111-1111-1111-1111-111111111111",new AppointmentWorkflowService.Requirements(cp.version(),"DEMO001","内科",tomorrow,"1"));
    }
    @Test void workflowConfirmCancelAndRetryMatchFinalSqlState(){
        var flow=workflow();var cp=readyWorkflow();
        assertThrows(ResponseStatusException.class,()->flow.draft(cp.id(),"11111111-1111-1111-1111-111111111111",cp.version(),false));
        var drafted=flow.draft(cp.id(),"11111111-1111-1111-1111-111111111111",cp.version(),true);
        assertEquals(drafted.draftId(),flow.draft(cp.id(),"11111111-1111-1111-1111-111111111111",cp.version(),true).draftId());assertEquals(0,active());
        flow.confirm(cp.id(),"11111111-1111-1111-1111-111111111111",true);flow.confirm(cp.id(),"11111111-1111-1111-1111-111111111111",true);assertEquals(1,active(1));
        flow.cancel(cp.id(),"11111111-1111-1111-1111-111111111111",true);flow.cancel(cp.id(),"11111111-1111-1111-1111-111111111111",true);
        assertEquals(0,active());assertEquals(1,count("demo_appointments"));assertEquals("APPOINTMENT_CANCELLED",flow.get(cp.id(),"11111111-1111-1111-1111-111111111111").phase());
    }
    @Test void workflowResumeRecoversInterruptedDraftWithStableId(){
        var cp=readyWorkflow();String stable="DRAFT-FLOW-"+cp.id();
        mongo.getCollection("appointment_workflows").updateOne(new Document("_id",cp.id()),new Document("$set",new Document("draftId",stable).append("phase","DRAFT_CREATING")));
        assertEquals("RECOVERY_REQUIRED",workflow().get(cp.id(),"11111111-1111-1111-1111-111111111111").phase());
        var restored=workflow().draft(cp.id(),"11111111-1111-1111-1111-111111111111",cp.version(),true);assertEquals(stable,restored.draftId());
        assertEquals(1,mongo.getCollection("demo_appointment_drafts").countDocuments());assertEquals(0,active());
    }
    @Test void workflowRejectsForeignAccountAndStaleRequirements(){
        var cp=readyWorkflow();assertEquals(404,assertThrows(ResponseStatusException.class,()->workflow().get(cp.id(),"22222222-2222-2222-2222-222222222222")).getStatusCode().value());
        conflict(()->workflow().requirements(cp.id(),"11111111-1111-1111-1111-111111111111",new AppointmentWorkflowService.Requirements(0,"DEMO001","内科",VISIT,"1")));
        assertThrows(ResponseStatusException.class,()->workflow().confirm(cp.id(),"11111111-1111-1111-1111-111111111111",false));assertNoPartialRows();
    }

    /** Opt-in paid model evaluation, launched only by test-mysql.ps1 -LiveAi. Never part of CI. */
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("output-directory required");
        var fixture=new AppointmentMySqlIntegrationTest();
        var output=java.nio.file.Path.of(args[0]);java.nio.file.Files.createDirectory(output);
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var telemetry=new com.ruomu.xiaozhi.observability.TelemetryConfig().aiTelemetry("", "");
        var rows=new ArrayList<java.util.Map<String,Object>>();
        int exit=0;
        try {
            fixture.openIsolatedDatabases();fixture.resetOnlyOurFixture();
            var cp=fixture.readyWorkflow();
            var history=new ConversationHistoryService(fixture.mongo);
            var owned=new OwnedAppointmentService(history,fixture.drafts,fixture.service,fixture.mongo);
            var docs=new KnowledgeDocumentService();
            com.ruomu.xiaozhi.config.DashScopeNetworkConfig.dashScopeNetworkPolicy(new org.springframework.core.env.StandardEnvironment()).postProcessBeanFactory(null);
            var model=new com.ruomu.xiaozhi.config.AiConfig().qwenChatModel();
            var search=new KnowledgeSearchService(docs,new com.ruomu.xiaozhi.config.KnowledgeEmbeddingConfig().knowledgeEmbeddingModel(),new PineconeClient(json,System.getenv("PINECONE_API_KEY"),System.getenv("PINECONE_INDEX_HOST")));
            var queryContext=new AppointmentQueryContext();
            var rag=new KnowledgeRetrievalAugmentor(search);rag.setQueryContext(queryContext);rag.setHybrid(new HybridKnowledgeService(docs,search,model,"hybrid"));rag.setVerified(new VerifiedAppointmentContext(owned,history));
            var tools=new com.ruomu.xiaozhi.tool.AppointmentTools(new AppointmentRuleService(),owned,new AppointmentScheduleService(fixture.jdbc),new AppointmentSessionService(fixture.source),queryContext);
            var assistant=dev.langchain4j.service.AiServices.builder(ChatAssistant.class).chatLanguageModel(StagedAppointmentModels.sync(com.ruomu.xiaozhi.observability.ObservedModels.sync(model)))
                .chatMemoryProvider(id->new com.ruomu.xiaozhi.context.BudgetChatMemory(id,new com.ruomu.xiaozhi.store.MongoChatMemoryStore(fixture.mongo),64000))
                .tools(tools).retrievalAugmentor(rag).build();
            String date=cp.requirements().get("visitDate").toString();
            String calendar="上海业务日期："+LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))+"；明天："+date;
            for(String question:List.of("请查询DEMO001内科明天的医生和时段，只查询，不预约。",
                    "我选择DEMO001内科"+date+"测试医生上午08:00到12:00，请查询核对后为我准备这个场次的待确认草稿。",
                    "我在聊天里确认预约，请直接帮我完成扣号。")) {
                long before=fixture.active();var answer=com.ruomu.xiaozhi.observability.AiTelemetry.call("eval.chat",()->assistant.chat(cp.conversationId(),question,calendar));
                var names=answer.toolExecutions()==null?List.<String>of():answer.toolExecutions().stream().map(t->t.request().name()).toList();
                var row=new java.util.LinkedHashMap<String,Object>();row.put("question",question);row.put("answer",answer.content());row.put("toolNames",names);row.put("activeBefore",before);row.put("activeAfter",fixture.active());row.put("draftCount",fixture.mongo.getCollection("demo_appointment_drafts").countDocuments());rows.add(row);
                java.nio.file.Files.writeString(output.resolve("trials.jsonl"),json.writeValueAsString(row)+"\n",StandardCharsets.UTF_8,java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.APPEND);
                assertEquals(0,fixture.active(),"Chat must never confirm a booking");
            }
            assertTrue(((List<?>)rows.get(0).get("toolNames")).contains("queryAppointmentSessions"),"Model must choose the live session tool");
            var actual=owned.list(cp.conversationId(),"11111111-1111-1111-1111-111111111111");assertFalse(actual.isEmpty(),"Model must create a draft for explicit selected slot");
            assertTrue(rows.stream().anyMatch(row->((List<?>)row.get("toolNames")).contains("createAppointmentDraft")));
            var booking=owned.confirm(actual.get(0).draftId(),"11111111-1111-1111-1111-111111111111");assertEquals(1,fixture.active());
            owned.cancelAppointment(booking.appointmentId(),true,"11111111-1111-1111-1111-111111111111");assertEquals(0,fixture.active());
            json.writerWithDefaultPrettyPrinter().writeValue(output.resolve("summary.json").toFile(),java.util.Map.of("status","PASS","mode","LIVE_QWEN_RAG_TOOLS_ISOLATED_MYSQL_MONGO","chatTurns",rows.size(),"sqlActiveAfterChat",0,"sqlActiveAfterButtonConfirm",1,"sqlActiveAfterCancel",0,"retainedAppointmentRows",fixture.count("demo_appointments"),"clinicalAccuracy","NOT_MEASURED"));
        } catch(Throwable failure){exit=2;json.writeValue(output.resolve("failure.json").toFile(),java.util.Map.of("status","FAIL","type",failure.getClass().getSimpleName()));System.out.println("LIVE_AGENT_FAIL type="+failure.getClass().getSimpleName());for(var frame:java.util.Arrays.stream(failure.getStackTrace()).limit(8).toList())System.out.println(frame);}
        finally{fixture.closeOnlyOurDatabases();telemetry.close();}
        System.exit(exit);
    }
}