package com.ruomu.xiaozhi.controller;

import com.ruomu.xiaozhi.dto.AppointmentDraftResponse;
import com.ruomu.xiaozhi.dto.AppointmentResponse;
import com.ruomu.xiaozhi.dto.CreateAppointmentRequest;
import com.ruomu.xiaozhi.service.AppointmentDraftService;
import com.ruomu.xiaozhi.service.AppointmentService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(
        value = "/api/appointments",
        produces = "application/json;charset=UTF-8"
)
public class AppointmentController {

    private final AppointmentService appointmentService;
    private final AppointmentDraftService draftService;

    public AppointmentController(
            AppointmentService appointmentService,
            AppointmentDraftService draftService) {

        this.appointmentService = appointmentService;
        this.draftService = draftService;
    }

    @PostMapping("/drafts")
    @ResponseStatus(HttpStatus.CREATED)
    public AppointmentDraftResponse createDraft(
            @RequestBody CreateAppointmentRequest request) {

        return draftService.createDraft(request);
    }

    @GetMapping("/drafts/{draftId}")
    public AppointmentDraftResponse findDraft(
            @PathVariable("draftId") String draftId) {

        return draftService.findById(draftId);
    }

    @PostMapping("/drafts/{draftId}/confirm")
    public AppointmentResponse confirm(
            @PathVariable("draftId") String draftId) {

        return draftService.confirm(draftId);
    }

    @GetMapping("/{appointmentId}")
    public AppointmentResponse findById(
            @PathVariable("appointmentId") String appointmentId) {

        return appointmentService.findById(appointmentId);
    }
}