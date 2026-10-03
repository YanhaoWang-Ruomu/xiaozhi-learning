package com.ruomu.xiaozhi.tool;

import com.ruomu.xiaozhi.dto.AppointmentDraftResponse;
import com.ruomu.xiaozhi.dto.AppointmentRuleResponse;
import com.ruomu.xiaozhi.dto.CreateAppointmentRequest;
import com.ruomu.xiaozhi.service.AppointmentDraftService;
import com.ruomu.xiaozhi.service.AppointmentRuleService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;

@Component
public class AppointmentTools {

    private final AppointmentRuleService ruleService;
    private final AppointmentDraftService draftService;

    public AppointmentTools(
            AppointmentRuleService ruleService,
            AppointmentDraftService draftService) {

        this.ruleService = ruleService;
        this.draftService = draftService;
    }

    @Tool("""
            根据用户明确提供的医院编号，查询本地预约规则。

            输入约定：
            医院编号是本系统的查询键，不是经过验证的官方编号。
            任意非空字符串都允许查询，没有固定前缀、长度或数字格式。
            UNKNOWN 也是合法的查询输入，不代表参数缺失。
            不得因为不认识编号或认为格式异常而跳过查询。
            是否存在配置，必须由本工具的实际返回结果判断。
            用户没有提供编号时，先询问，不自行构造编号。

            返回状态：
            DEMO_DATA：查到虚构教学配置，必须说明仅用于演示。
            NO_DATA：本系统没有对应资料，不代表医院不存在。
            INVALID_INPUT：缺少医院编号。

            本工具不联网、不查询实时号源，也不能办理预约。
            没有资料时，不猜测放号天数、时间或其他医院信息。
            """)
    public AppointmentRuleResponse queryAppointmentRule(
            @P("用户明确提供的医院编号；任意非空字符串均可，包括 UNKNOWN")
            String hospitalId) {

        AppointmentRuleResponse result;

        if (hospitalId == null || hospitalId.isBlank()) {
            result = new AppointmentRuleResponse(
                    "INVALID_INPUT",
                    hospitalId,
                    null,
                    null,
                    null,
                    null,
                    "未执行查询",
                    "缺少医院编号，请先询问用户。"
            );
        } else {
            result = ruleService.findRule(hospitalId);
        }

        System.out.println(
                "[AppointmentTools] queryAppointmentRule 已执行，状态="
                        + result.status()
        );

        return result;
    }

    @Tool("""
            根据用户明确提出的预约需求，生成本地演示预约草稿。

            调用条件：
            用户明确要求预约或生成预约草稿，
            且已提供医院编号、科室、具体年月日。
            仅咨询预约规则时，不调用本工具。
            缺少信息时先询问，不擅自补全。
            日期必须是用户明确提供的具体日期，格式 yyyy-MM-dd。
            如果用户只说“明天”“后天”等相对日期，
            请先询问具体年月日，不猜测当前日期。

            当前演示仅支持 DEMO001、内科。
            日期是否在允许范围内，由后端校验。

            返回状态：
            PENDING_CONFIRMATION：草稿已保存，尚未创建预约。
            INVALID_INPUT：参数不符合要求，本次未生成草稿。

            成功后必须展示工具实际返回的草稿编号、医院编号、
            科室、日期和时区，并说明需要用户单独确认。
            不自行编造或修改草稿编号。

            本工具只能生成草稿，不能确认预约。
            用户在聊天中说“确认”也不能使本工具办理预约。
            不声称真实挂号成功，不承诺号源。
            """)
    public Map<String, Object> createAppointmentDraft(
            @P("用户明确提供的医院编号")
            String hospitalId,

            @P("用户明确选择的科室")
            String department,

            @P("用户明确提供的具体日期，格式 yyyy-MM-dd")
            String visitDate) {

        if (hospitalId == null || hospitalId.isBlank()
                || department == null || department.isBlank()
                || visitDate == null || visitDate.isBlank()) {

            return invalidInput("请补充医院编号、科室和具体预约日期。");
        }

        LocalDate parsedDate;

        try {
            parsedDate = LocalDate.parse(visitDate.strip());
        } catch (DateTimeParseException exception) {
            return invalidInput("预约日期必须是有效日期，格式为 yyyy-MM-dd。");
        }

        try {
            AppointmentDraftResponse draft = draftService.createDraft(
                    new CreateAppointmentRequest(
                            hospitalId,
                            department,
                            parsedDate
                    )
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
                    reason == null ? "预约参数不符合要求。" : reason
            );
        }
    }

    private Map<String, Object> invalidInput(String message) {

        System.out.println(
                "[AppointmentTools] createAppointmentDraft 参数校验失败"
        );

        return Map.of(
                "status", "INVALID_INPUT",
                "message", message
        );
    }
}