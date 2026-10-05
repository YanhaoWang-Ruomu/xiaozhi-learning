package com.ruomu.xiaozhi.controller;

import com.ruomu.xiaozhi.dto.AppointmentSessionResponse;
import com.ruomu.xiaozhi.service.AppointmentSessionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AppointmentSessionController {
    private final AppointmentSessionService sessionService;

    public AppointmentSessionController(AppointmentSessionService sessionService) {
        this.sessionService = sessionService;
    }

    @GetMapping("/api/appointment/sessions")
    public AppointmentSessionResponse getSessions(
            @RequestParam(name = "hospitalId") String hospitalId,
            @RequestParam(name = "department") String department) {
        return sessionService.findSessions(hospitalId, department);
    }
}
