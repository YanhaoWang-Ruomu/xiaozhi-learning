package com.ruomu.xiaozhi.tool;

import com.ruomu.xiaozhi.dto.AppointmentDraftResponse;
import com.ruomu.xiaozhi.dto.AppointmentRuleResponse;
import com.ruomu.xiaozhi.dto.AppointmentScheduleResponse;
import com.ruomu.xiaozhi.dto.AppointmentSessionResponse;
import com.ruomu.xiaozhi.dto.CreateAppointmentRequest;
import com.ruomu.xiaozhi.service.OwnedAppointmentService;
import com.ruomu.xiaozhi.service.AppointmentRuleService;
import com.ruomu.xiaozhi.service.AppointmentScheduleService;
import com.ruomu.xiaozhi.service.AppointmentSessionService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;

@Component
public class AppointmentTools {

    private final AppointmentRuleService ruleService;
    private final OwnedAppointmentService draftService;
    private final AppointmentScheduleService scheduleService;
    private final AppointmentSessionService sessionService;

    public AppointmentTools(
            AppointmentRuleService ruleService,
            OwnedAppointmentService draftService,
            AppointmentScheduleService scheduleService,
            AppointmentSessionService sessionService) {

        this.ruleService = ruleService;
        this.draftService = draftService;
        this.scheduleService = scheduleService;
        this.sessionService = sessionService;
    }

    @Tool("""
            查询本地教学配置中的医院预约规则。
            用户明确提供非空医院编号时即可查询，包括 UNKNOWN 等未知编号。
            不根据编号格式自行判断医院是否存在。
            如果用户没有提供医院编号，应先询问。
            DEMO_DATA 表示虚构的演示资料，不是真实医院官方信息。
            NO_DATA 表示本地没有资料，不代表医院编号无效。
            此工具没有实时医院信息或实时号源查询能力。
            """)
    public AppointmentRuleResponse queryAppointmentRule(
            @P("用户明确提供的医院编号") String hospitalId) {
        return com.ruomu.xiaozhi.observability.AiTelemetry.call("tool.queryAppointmentRule", () -> {

        if (hospitalId == null || hospitalId.isBlank()) {
            return new AppointmentRuleResponse(
                    "INVALID_INPUT",
                    null,
                    null,
                    null,
                    null,
                    null,
                    "无已配置资料",
                    "请先提供医院编号。"
            );
        }

        AppointmentRuleResponse response = ruleService.findRule(
                hospitalId.strip()
        );

        System.out.println(
                "[AppointmentTools] queryAppointmentRule 已执行，状态="
                        + response.status()
        );

        return response;

        });
    }

    @Tool("""
            查询本地虚构医院未来三天按日期汇总的演示名额。
            此工具不提供具体医生、时段或场次编号。
            需要医生时段或准备草稿时，应使用 queryAppointmentSessions。
            用户必须明确提供医院编号和科室；缺少时先询问，不擅自补全。
            包括未知编号在内，都应依据工具结果回答，不自行猜测是否存在。
            DEMO_DATA 仅代表本地演示数据，不是真实医院号源。
            referenceRemaining 是总名额减去未取消预约数后的参考值。
            每日总余量不代表某个医生时段有余量，不能据此承诺某场次可约。
            草稿不锁定名额；本工具不创建草稿，不确认或取消预约。
            NO_DATA 表示没有配置排班，不等于满额或医院不存在。
            返回空白或失败时不编造日期、名额和剩余数量。
            """)
    public AppointmentScheduleResponse queryAppointmentSchedules(
            @P("用户明确提供的医院编号") String hospitalId,
            @P("用户明确提供的科室") String department) {
        return com.ruomu.xiaozhi.observability.AiTelemetry.call("tool.queryAppointmentSchedules", () -> {

        AppointmentScheduleResponse response =
                scheduleService.findSchedules(hospitalId, department);

        System.out.println(
                "[AppointmentTools] queryAppointmentSchedules 已执行，状态="
                        + response.status()
        );

        return response;

        });
    }

    @Tool("""
            查询本地虚构医院未来三天的具体医生、时段、场次编号及参考余量。
            医院编号和科室必须来自用户明确提供的信息；未知编号也按工具结果回答。
            返回 data.sessions；sessionId 是草稿所需场次编号，不是预约编号。
            只展示用户所问日期、医生或时段；信息不足时请用户选择，不替用户决定。
            DEMO_DATA 是虚构教学数据，NO_DATA 是没有配置资料，不等于满额。
            referenceRemaining 是场次剩余与当天共享剩余的较小值；查询不预留名额。
            AVAILABLE 表示查询时可申请，不保证最终成功。
            NOT_RELEASED 表示尚未放号，可准备有余量的草稿但不能确认。
            FULL 表示满额，NO_SCHEDULE 表示缺少当天总排班，不创建对应草稿。
            不从文档、旧消息或示例中编造医生、时段、场次编号和余量。
            此工具只查询本地数据，不创建草稿，不确认或取消预约。
            """)
    public Map<String, Object> queryAppointmentSessions(
            @P("用户明确提供的医院编号") String hospitalId,
            @P("用户明确提供的科室") String department) {
        return com.ruomu.xiaozhi.observability.AiTelemetry.call("tool.queryAppointmentSessions", () -> {

        try {
            AppointmentSessionResponse response =
                    sessionService.findSessions(hospitalId, department);
            System.out.println("[AppointmentTools] queryAppointmentSessions 已执行，状态="
                    + response.status());
            return Map.of("status", response.status(), "data", response);
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode().value() != 400) throw exception;
            return invalidInput(exception.getReason() == null
                    ? "请提供医院编号和科室。" : exception.getReason());
        }

        });
    }

    @Tool("""
            为用户明确选择的具体场次创建待确认的本地演示预约草稿。
            仅在用户明确要求准备预约，并已选择医院、科室、日期、医生和时段时调用。
            先使用 queryAppointmentSessions 查询，sessionId 必须取自匹配用户选择的结果。
            用户未指定医生或时段时先询问，不能自动选第一个；不要求用户手填内部编号。
            当前仅支持 DEMO001、内科以及业务日期范围内的演示场次。
            visitDate 使用 yyyy-MM-dd；明确的明天、后天、大后天按本轮服务器日期表换算。
            不根据电脑本地日期或历史消息自行推算；含糊日期需要用户澄清。
            不擅自补全医院、科室、医生、时段，不改用按日期预约绕过场次校验。
            未放号但有余量时可准备草稿；满额或缺少排班时不能创建。
            仅 PENDING_CONFIRMATION 表示草稿生成成功，尚未预约且不占号。
            INVALID_INPUT 表示没有创建草稿，应说明原因并请用户重新选择。
            此工具不能确认或取消；最终仍由用户核对网页详情并点击独立确认按钮。
            会话编号由程序自动传入，不向用户询问，不由模型指定。
            """)
    public Map<String, Object> createAppointmentDraft(
            @P("用户明确选择的医院编号") String hospitalId,
            @P("用户明确选择的科室") String department,
            @P("具体预约日期，yyyy-MM-dd；明确相对日期按本轮服务器日期表换算") String visitDate,
            @P("从最新场次查询结果中取得、匹配用户所选日期医生时段的sessionId") String sessionId,
            @ToolMemoryId String conversationId) {
        return com.ruomu.xiaozhi.observability.AiTelemetry.call("tool.createAppointmentDraft", () -> {

        if (conversationId == null || conversationId.isBlank()) {
            return invalidInput(
                    "当前请求缺少会话编号，请检查聊天接口配置。"
            );
        }

        if (hospitalId == null || hospitalId.isBlank()
                || department == null || department.isBlank()
                || visitDate == null || visitDate.isBlank()
                || sessionId == null || sessionId.isBlank()) {

            return invalidInput(
                    "请先查询场次并明确选择医院、科室、日期、医生和时段；不能缺少场次编号。"
            );
        }

        String target = sessionId.strip();
        if (!target.matches("[1-9][0-9]{0,18}")) {
            return invalidInput("场次编号格式错误，请重新查询并选择，不能自行编造编号。");
        }
        try {
            Long.parseLong(target);
        } catch (NumberFormatException exception) {
            return invalidInput("场次编号超出范围，请重新查询场次。");
        }
        if (!visitDate.strip().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            return invalidInput("预约日期格式不正确，请使用 yyyy-MM-dd。");
        }

        LocalDate date;

        try {
            date = LocalDate.parse(visitDate.strip());
        } catch (DateTimeParseException exception) {
            return invalidInput(
                    "预约日期格式不正确，请使用 yyyy-MM-dd。"
            );
        }

        try {
            // 在真正创建草稿前重新核对当前窗口和场次，查询不占号。
            AppointmentSessionResponse response =
                    sessionService.findSessions(hospitalId, department);
            var selected = response.sessions().stream()
                    .filter(item -> target.equals(item.sessionId()) && date.equals(item.visitDate()))
                    .findFirst().orElse(null);
            if (selected == null) {
                return invalidInput("该场次与医院、科室或日期不匹配，或已不在当前可选窗口；请重新查询并选择。");
            }
            if (!response.bookingEnabled()
                    || !("AVAILABLE".equals(selected.bookingStatus())
                    || "NOT_RELEASED".equals(selected.bookingStatus()))
                    || selected.referenceRemaining() <= 0) {
                return invalidInput("该场次当前无可用名额或缺少排班，请重新查询并选择其他场次。");
            }

            AppointmentDraftResponse draft = draftService.createFromChat(
                    new CreateAppointmentRequest(
                            hospitalId,
                            department,
                            date,
                            target
                    ),
                    conversationId
            );

            System.out.println(
                    "[AppointmentTools] createAppointmentDraft 已执行，状态="
                            + draft.status()
                            + "，草稿编号="
                            + draft.draftId()
            );

            return Map.of(
                    "status", draft.status(),
                    "draft", draft
            );

        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode().value() != 400) {
                throw exception;
            }

            String reason = exception.getReason();

            return invalidInput(
                    reason == null
                            ? "预约参数不符合演示规则。"
                            : reason
            );
        }

        });
    }

    private Map<String, Object> invalidInput(String message) {
        System.out.println(
                "[AppointmentTools] 输入校验未通过，未创建草稿，原因="
                        + message
        );

        return Map.of(
                "status", "INVALID_INPUT",
                "message", message
        );
    }
}
