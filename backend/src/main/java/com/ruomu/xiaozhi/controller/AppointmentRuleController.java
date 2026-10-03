package com.ruomu.xiaozhi.controller;

import com.ruomu.xiaozhi.dto.AppointmentRuleResponse;
import com.ruomu.xiaozhi.service.AppointmentRuleService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class AppointmentRuleController {

    private final AppointmentRuleService ruleService;

    public AppointmentRuleController(
            AppointmentRuleService ruleService) {
        this.ruleService = ruleService;
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
}