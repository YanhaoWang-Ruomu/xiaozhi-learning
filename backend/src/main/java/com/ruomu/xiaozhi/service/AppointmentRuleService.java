package com.ruomu.xiaozhi.service;

import com.ruomu.xiaozhi.dto.AppointmentRuleResponse;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class AppointmentRuleService {

    private final Map<String, AppointmentRuleResponse> rules = Map.of(
            "DEMO001",
            new AppointmentRuleResponse(
                    "DEMO_DATA",
                    "DEMO001",
                    "学习演示医院（虚构）",
                    3,
                    "09:30",
                    "Asia/Shanghai",
                    "本地教学配置，非真实医院资料",
                    "仅用于测试：提前3天，每天09:30放号，不代表实际号源。"
            )
    );

    public AppointmentRuleResponse findRule(String hospitalId) {
        String id = hospitalId.strip();

        AppointmentRuleResponse rule = rules.get(id);

        if (rule != null) {
            return rule;
        }

        return new AppointmentRuleResponse(
                "NO_DATA",
                id,
                null,
                null,
                null,
                null,
                "无已配置资料",
                "暂无该医院的预约规则，不能确认放号天数或时间，请以医院官方渠道为准。"
        );
    }
}