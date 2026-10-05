package com.ruomu.xiaozhi.tool;

import com.ruomu.xiaozhi.dto.AppointmentDraftResponse;
import com.ruomu.xiaozhi.dto.AppointmentRuleResponse;
import com.ruomu.xiaozhi.dto.AppointmentScheduleResponse;
import com.ruomu.xiaozhi.dto.CreateAppointmentRequest;
import com.ruomu.xiaozhi.service.AppointmentDraftService;
import com.ruomu.xiaozhi.service.AppointmentRuleService;
import com.ruomu.xiaozhi.service.AppointmentScheduleService;
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
    private final AppointmentDraftService draftService;
    private final AppointmentScheduleService scheduleService;

    public AppointmentTools(
            AppointmentRuleService ruleService,
            AppointmentDraftService draftService,
            AppointmentScheduleService scheduleService) {

        this.ruleService = ruleService;
        this.draftService = draftService;
        this.scheduleService = scheduleService;
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
    }

    @Tool("""
            查询本地虚构医院未来三天的演示排班和参考剩余数量。
            用户必须明确提供医院编号和科室；缺少时先询问，不擅自补全。
            包括未知编号在内，都应依据工具结果回答，不自行猜测是否存在。
            DEMO_DATA 仅代表本地演示数据，不是真实医院号源。
            referenceRemaining 是总名额减去未取消预约数后的参考值。
            capacityEnforced=false 表示系统尚未限制超额预约，不能承诺预约成功。
            草稿不锁定名额；本工具不创建草稿，不确认或取消预约。
            NO_DATA 表示没有配置排班，不等于满额或医院不存在。
            返回空白或失败时不编造日期、名额和剩余数量。
            """)
    public AppointmentScheduleResponse queryAppointmentSchedules(
            @P("用户明确提供的医院编号") String hospitalId,
            @P("用户明确提供的科室") String department) {

        AppointmentScheduleResponse response =
                scheduleService.findSchedules(hospitalId, department);

        System.out.println(
                "[AppointmentTools] queryAppointmentSchedules 已执行，状态="
                        + response.status()
        );

        return response;
    }

    @Tool("""
            创建待用户确认的本地演示预约草稿。
            仅当用户明确要求准备预约，并已明确提供医院编号、
            科室和具体预约日期时调用。
            当前仅支持医院 DEMO001、科室内科。
            日期必须使用 yyyy-MM-dd 格式。
            用户只说“明天”等相对日期时，应先询问具体日期，不自行猜测。
            不擅自补全医院、科室或日期。
            成功只代表生成待确认草稿，不代表预约已创建。
            只有实际返回 PENDING_CONFIRMATION 时才能说明草稿生成成功。
            返回 INVALID_INPUT 时应说明问题，不能声称成功。
            此工具不能确认预约，用户需要点击网页上的独立确认按钮。
            会话编号由程序自动传入，不需要向用户询问。
            """)
    public Map<String, Object> createAppointmentDraft(
            @P("用户明确选择的医院编号") String hospitalId,
            @P("用户明确选择的科室") String department,
            @P("用户明确提供的预约日期，格式 yyyy-MM-dd") String visitDate,
            @ToolMemoryId String conversationId) {

        if (conversationId == null || conversationId.isBlank()) {
            return invalidInput(
                    "当前请求缺少会话编号，请检查聊天接口配置。"
            );
        }

        if (hospitalId == null || hospitalId.isBlank()
                || department == null || department.isBlank()
                || visitDate == null || visitDate.isBlank()) {

            return invalidInput(
                    "请明确提供医院编号、科室和具体预约日期。"
            );
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
            AppointmentDraftResponse draft = draftService.createDraft(
                    new CreateAppointmentRequest(
                            hospitalId,
                            department,
                            date
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
    }

    private Map<String, Object> invalidInput(String message) {
        System.out.println(
                "[AppointmentTools] createAppointmentDraft 未创建草稿，原因="
                        + message
        );

        return Map.of(
                "status", "INVALID_INPUT",
                "message", message
        );
    }
}