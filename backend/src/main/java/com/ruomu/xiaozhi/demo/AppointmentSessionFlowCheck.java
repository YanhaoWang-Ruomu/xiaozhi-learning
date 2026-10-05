package com.ruomu.xiaozhi.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

public class AppointmentSessionFlowCheck {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BASE = System.getProperty(
            "xiaozhi.check.base-url", "http://127.0.0.1:8081");
    private static final String SCHEDULES =
            "/api/appointment/sessions?hospitalId=DEMO001&department=%E5%86%85%E7%A7%91";
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final String conversationId = "CHECK-" + UUID.randomUUID();
    private final Set<String> draftIds = new LinkedHashSet<>();
    private boolean creationAttempted;
    private String selectedSessionId;
    private String peerId;
    private long baselineActive;
    private long baselineDaily;
    private long baselineSession;
    private long peerActive;
    private long peerRemaining;
    private int passed;
    private int skipped;

    public static void main(String[] args) throws Exception {
        URI base = URI.create(BASE);
        if (!"http".equals(base.getScheme()) || base.getUserInfo() != null
                || base.getQuery() != null || base.getFragment() != null
                || !(base.getPath().isEmpty() || "/".equals(base.getPath()))
                || !Set.of("127.0.0.1", "localhost", "[::1]").contains(base.getHost())) {
            throw new IllegalArgumentException("检查程序只允许连接本机HTTP后端");
        }
        new AppointmentSessionFlowCheck().run();
    }

    private void run() throws Exception {
        System.out.println("测试会话：" + conversationId);
        System.out.println("检查期间请暂停其他预约操作，以免干扰余号比较。");
        Exception failure = null;
        try {
            checkFlow();
        } catch (Exception exception) {
            failure = exception;
        } finally {
            try {
                cleanUp();
            } catch (Exception exception) {
                if (failure == null) failure = exception;
                else failure.addSuppressed(exception);
            }
        }
        if (failure != null) {
            System.err.println("APPOINTMENT_SESSION_FLOW_CHECK_FAILED 会话=" + conversationId);
            throw failure;
        }
        System.out.println("APPOINTMENT_SESSION_FLOW_CHECK_PASSED passed=" + passed + " skipped=" + skipped);
    }

    private void checkFlow() throws Exception {
        JsonNode rule = call("GET", "/api/appointment/rules?hospitalId=DEMO001", null, 200);
        require("DEMO_DATA".equals(rule.path("status").asText())
                && rule.path("advanceDays").asInt() == 3
                && "09:30".equals(rule.path("releaseTime").asText())
                && "Asia/Shanghai".equals(rule.path("timeZone").asText()), "规则接口不符合预期");
        pass("规则接口：提前3天、上海09:30放号");

        JsonNode schedules = call("GET", SCHEDULES, null, 200);
        require(schedules.path("bookingEnabled").asBoolean()
                && schedules.path("sessions").isArray()
                && !schedules.path("sessions").isEmpty(), "场次预约未启用或没有排班");
        JsonNode selected = null;
        JsonNode unreleased = null;
        for (JsonNode item : schedules.path("sessions")) {
            if (selected == null && item.path("bookable").asBoolean()
                    && "AVAILABLE".equals(item.path("bookingStatus").asText())
                    && item.path("referenceRemaining").asLong() > 0) selected = item;
            if ("NOT_RELEASED".equals(item.path("bookingStatus").asText())) unreleased = item;
        }
        require(selected != null, "当前没有可预约名额，本轮无法验证确认流程；请稍后重试");
        String date = selected.path("visitDate").asText();
        long baseline = selected.path("activeAppointmentCount").asLong();
        long remaining = selected.path("referenceRemaining").asLong();
        selectedSessionId = selected.path("sessionId").asText();
        baselineActive = baseline;
        baselineDaily = selected.path("dailyRemaining").asLong();
        baselineSession = selected.path("sessionRemaining").asLong();
        for (JsonNode item : schedules.path("sessions")) {
            if (date.equals(item.path("visitDate").asText())
                    && !selectedSessionId.equals(item.path("sessionId").asText())) {
                peerId = item.path("sessionId").asText();
                peerActive = item.path("activeAppointmentCount").asLong();
                peerRemaining = item.path("sessionRemaining").asLong();
                break;
            }
        }
        require(peerId != null, "同一天至少需要另一场次，才能检查场次独立计数");
        pass("选择场次 " + selectedSessionId + "，日期 " + date);

        JsonNode draft = createDraft(date, selectedSessionId);
        String id = draft.path("draftId").asText();
        String draftPath = "/api/appointments/drafts/" + id;
        require("PENDING_CONFIRMATION".equals(draft.path("status").asText())
                && draft.path("appointmentId").isNull(), "生成草稿时不应创建预约");
        assertCounts(date, baseline, remaining);
        pass("创建草稿不占名额");

        JsonNode first = call("POST", draftPath + "/confirm", null, 200);
        String appointmentId = first.path("appointmentId").asText();
        require("DEMO_CREATED".equals(first.path("status").asText())
                && !appointmentId.isBlank(), "确认预约失败");
        require(first.path("session").equals(draft.path("session")), "预约场次与草稿核对信息不一致");
        JsonNode second = call("POST", draftPath + "/confirm", null, 200);
        require(appointmentId.equals(second.path("appointmentId").asText()), "重复确认生成了不同编号");
        assertCounts(date, baseline + 1, remaining - 1);
        pass("确认及重复确认只占用一个场次名额，编号一致");
        pass("其他场次独立计数，当天总余量同时减少一个");

        ObjectNode confirmation = JSON.createObjectNode().put("confirmed", true);
        String appointmentPath = "/api/appointments/" + appointmentId;
        JsonNode cancelled = call("POST", appointmentPath + "/cancel", confirmation, 200);
        JsonNode repeated = call("POST", appointmentPath + "/cancel", confirmation, 200);
        String cancelledAt = cancelled.path("cancelledAt").asText();
        require("DEMO_CANCELLED".equals(cancelled.path("status").asText())
                && "DEMO_CANCELLED".equals(repeated.path("status").asText())
                && cancelled.path("cancelledAt").isTextual()
                && repeated.path("cancelledAt").isTextual()
                && !cancelledAt.isBlank()
                && cancelledAt.equals(repeated.path("cancelledAt").asText()), "重复取消结果不一致");
        assertCounts(date, baseline, remaining);
        pass("取消及重复取消只返还一个名额，取消时间不变");

        JsonNode cancelledDraft = call("GET", draftPath, null, 200);
        require("APPOINTMENT_CANCELLED".equals(cancelledDraft.path("status").asText()), "关联草稿未反映取消状态");
        call("POST", draftPath + "/confirm", null, 409);
        require("DEMO_CANCELLED".equals(call("GET", appointmentPath, null, 200)
                .path("status").asText()), "已取消预约被恢复");
        pass("已取消预约不能通过原草稿再次确认恢复");

        String otherId = createDraft(date, selectedSessionId).path("draftId").asText();
        String otherPath = "/api/appointments/drafts/" + otherId;
        call("POST", otherPath + "/cancel", null, 200);
        call("POST", otherPath + "/cancel", null, 200);
        JsonNode closed = call("GET", otherPath, null, 200);
        require("CANCELLED".equals(closed.path("status").asText())
                && closed.path("appointmentId").isNull(), "草稿取消状态异常");
        call("POST", otherPath + "/confirm", null, 409);
        assertCounts(date, baseline, remaining);
        pass("取消草稿不产生预约，重复取消和再次确认结果正确");

        if (unreleased == null) {
            skipped++;
            System.out.println("SKIP 当前没有未放号日期，不修改电脑时间；边界由 AppointmentReleaseRuleCheck 检查");
        } else {
            String futureId = createDraft(unreleased.path("visitDate").asText(), unreleased.path("sessionId").asText()).path("draftId").asText();
            String futurePath = "/api/appointments/drafts/" + futureId;
            call("POST", futurePath + "/confirm", null, 409);
            JsonNode pending = call("GET", futurePath, null, 200);
            require("PENDING_CONFIRMATION".equals(pending.path("status").asText())
                    && pending.path("appointmentId").isNull(), "未放号确认应保留待确认草稿");
            pass("未放号确认返回409，草稿保留待确认");
        }
    }

    private JsonNode createDraft(String date, String sessionId) throws Exception {
        creationAttempted = true;
        JsonNode draft = call("POST", "/api/appointments/drafts?conversationId=" + conversationId,
                JSON.createObjectNode().put("hospitalId", "DEMO001")
                        .put("department", "内科").put("visitDate", date).put("sessionId", sessionId), 201);
        String id = draft.path("draftId").asText();
        require(id.matches("DRAFT-[0-9a-fA-F-]{36}"), "草稿编号异常");
        draftIds.add(id);
        require(date.equals(draft.path("visitDate").asText()), "草稿日期与请求不一致");
        require(sessionId.equals(draft.path("session").path("sessionId").asText()), "草稿未返回正确场次");
        System.out.println("测试草稿：" + id);
        return draft;
    }

    private void assertCounts(String date, long active, long remaining) throws Exception {
        boolean selectedFound = false;
        boolean peerFound = false;
        long delta = active - baselineActive;
        for (JsonNode item : call("GET", SCHEDULES, null, 200).path("sessions")) {
            if (selectedSessionId.equals(item.path("sessionId").asText())) {
                selectedFound = true;
                require(date.equals(item.path("visitDate").asText())
                                && item.path("activeAppointmentCount").asLong() == active
                                && item.path("referenceRemaining").asLong() == remaining
                                && item.path("sessionRemaining").asLong() == baselineSession - delta
                                && item.path("dailyRemaining").asLong() == baselineDaily - delta,
                        "所选场次计数不符合预期；请暂停其他预约操作");
            }
            if (peerId.equals(item.path("sessionId").asText())) {
                peerFound = true;
                require(item.path("activeAppointmentCount").asLong() == peerActive
                                && item.path("sessionRemaining").asLong() == peerRemaining
                                && item.path("dailyRemaining").asLong() == baselineDaily - delta,
                        "其他场次的独立计数或共享每日余量不符合预期");
            }
        }
        require(selectedFound && peerFound, "场次已离开查询窗口或被停用，请重新运行");
    }

    private void cleanUp() throws Exception {
        if (!creationAttempted) return;
        Exception failure = null;
        try {
            JsonNode list = call("GET", "/api/appointments/drafts?conversationId=" + conversationId, null, 200);
            require(list.isArray(), "清理时会话查询结果异常");
            for (JsonNode draft : list) draftIds.add(draft.path("draftId").asText());
        } catch (Exception exception) {
            failure = exception;
        }
        for (String id : draftIds) {
            try {
                require(id.matches("DRAFT-[0-9a-fA-F-]{36}"), "清理时草稿编号异常");
                String path = "/api/appointments/drafts/" + id;
                JsonNode draft = call("GET", path, null, 200);
                switch (draft.path("status").asText()) {
                    case "PENDING_CONFIRMATION" -> call("POST", path + "/cancel", null, 200);
                    case "CONFIRMED" -> call("POST", "/api/appointments/"
                                    + draft.path("appointmentId").asText() + "/cancel",
                            JSON.createObjectNode().put("confirmed", true), 200);
                    case "CANCELLED", "APPOINTMENT_CANCELLED" -> { }
                    default -> throw new IllegalStateException("草稿状态未确定，请手动核对：" + id);
                }
                JsonNode latest = call("GET", path, null, 200);
                require(Set.of("CANCELLED", "APPOINTMENT_CANCELLED")
                        .contains(latest.path("status").asText()), "清理后仍有未关闭草稿：" + id);
            } catch (Exception exception) {
                System.err.println("CLEANUP_FAILED " + id + " " + exception.getMessage());
                if (failure == null) failure = exception;
                else failure.addSuppressed(exception);
            }
        }
        if (failure != null) throw failure;
        System.out.println("CLEANUP_OK 本次已查询到的测试草稿和预约均已取消，历史记录保留");
    }

    private static JsonNode call(String method, String path, JsonNode body, int expected) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(BASE.replaceAll("/+$", "") + path))
                .timeout(Duration.ofSeconds(20)).header("Accept", "application/json");
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        HttpResponse<String> response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        require(response.statusCode() == expected,
                method + " " + path + " 预期HTTP " + expected + "，实际 " + response.statusCode());
        // 错误响应正文不要求固定结构，避免依赖Spring默认错误消息配置。
        return expected >= 400 ? JSON.nullNode() : JSON.readTree(response.body());
    }

    private void pass(String message) {
        passed++;
        System.out.println("PASS " + message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}