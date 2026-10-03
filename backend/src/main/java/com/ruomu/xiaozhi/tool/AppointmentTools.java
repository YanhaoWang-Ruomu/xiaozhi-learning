package com.ruomu.xiaozhi.tool;

import com.ruomu.xiaozhi.dto.AppointmentRuleResponse;
import com.ruomu.xiaozhi.service.AppointmentRuleService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

@Component
public class AppointmentTools {

    private final AppointmentRuleService ruleService;

    public AppointmentTools(AppointmentRuleService ruleService) {
        this.ruleService = ruleService;
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
            NO_DATA：编号可以查询，但本系统没有对应资料；
                     不代表编号格式错误，也不代表医院不存在。
            INVALID_INPUT：输入为 null、空字符串或全为空白。

            本工具不联网、不查询实时号源，也不能办理预约。
            没有资料时，不猜测放号天数、时间或其他医院信息。
            """)
    public AppointmentRuleResponse queryAppointmentRule(
            @P("用户明确提供的医院编号原文；任意非空字符串均可，包括 UNKNOWN")
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
}