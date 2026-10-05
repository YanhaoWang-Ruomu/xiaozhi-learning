package com.ruomu.xiaozhi.service;

import com.ruomu.xiaozhi.dto.AppointmentRuleResponse;
import org.springframework.stereotype.Service;

@Service
public class AppointmentRuleService {

    public AppointmentRuleResponse findRule(String hospitalId) {
        String id = hospitalId == null ? "" : hospitalId.strip();

        if (id.isEmpty()) {
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

        if (!AppointmentBookingPolicy.HOSPITAL_ID.equals(id)) {
            return new AppointmentRuleResponse(
                    "NO_DATA",
                    id,
                    null,
                    null,
                    null,
                    null,
                    "无已配置资料",
                    "暂无该医院的预约规则，请以医院官方渠道为准。"
            );
        }

        return new AppointmentRuleResponse(
                "DEMO_DATA",
                id,
                "学习演示医院（虚构）",
                AppointmentBookingPolicy.ADVANCE_DAYS,
                AppointmentBookingPolicy.RELEASE_TIME.toString(),
                AppointmentBookingPolicy.ZONE.getId(),
                "本地教学规则，非真实医院资料",
                "每个就诊日期提前"
                        + AppointmentBookingPolicy.ADVANCE_DAYS
                        + "天、上海时间"
                        + AppointmentBookingPolicy.RELEASE_TIME
                        + "开放；开放后在预约日期范围内持续可约，满额除外。"
                        + "仅支持明天起的未来"
                        + AppointmentBookingPolicy.ADVANCE_DAYS
                        + "天，不支持当天预约。"
                        + "草稿不占名额，未放号可保留草稿等待；"
                        + "确认还需排班存在且名额充足。仅用于本地演示。"
        );
    }
}