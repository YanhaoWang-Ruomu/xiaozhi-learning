package com.ruomu.xiaozhi.controller;

import com.ruomu.xiaozhi.dto.AppointmentRuleResponse;
import com.ruomu.xiaozhi.dto.AppointmentScheduleResponse;
import com.ruomu.xiaozhi.service.AppointmentRuleService;
import com.ruomu.xiaozhi.service.AppointmentScheduleService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class AppointmentRuleController {

    private final AppointmentRuleService ruleService;
    private final AppointmentScheduleService scheduleService;

    public AppointmentRuleController(
            AppointmentRuleService ruleService,
            AppointmentScheduleService scheduleService) {

        this.ruleService = ruleService;
        this.scheduleService = scheduleService;
    }

    @GetMapping("/api/appointment/rules")
    public AppointmentRuleResponse getRules(
            @RequestParam(name = "hospitalId") String hospitalId) {

        if (hospitalId.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "hospitalId 不能为空"
            );
        }

        return ruleService.findRule(hospitalId);
    }

    @GetMapping("/api/appointment/schedules")
    public AppointmentScheduleResponse getSchedules(
            @RequestParam(name = "hospitalId") String hospitalId,
            @RequestParam(name = "department") String department) {

        AppointmentScheduleResponse response =
                scheduleService.findSchedules(hospitalId, department);

        if ("INVALID_INPUT".equals(response.status())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    response.message()
            );
        }

        return response;
    }
}