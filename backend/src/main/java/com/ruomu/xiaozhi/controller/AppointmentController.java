package com.ruomu.xiaozhi.controller;

import com.ruomu.xiaozhi.dto.AppointmentDraftResponse;
import com.ruomu.xiaozhi.dto.AppointmentResponse;
import com.ruomu.xiaozhi.dto.CancelAppointmentRequest;
import com.ruomu.xiaozhi.dto.CreateAppointmentRequest;
import com.ruomu.xiaozhi.service.AppointmentDraftService;
import com.ruomu.xiaozhi.service.AppointmentService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
            @RequestBody CreateAppointmentRequest request,
            @RequestParam(
                    name = "conversationId",
                    required = false
            ) String conversationId) {

        if (conversationId == null) {
            return draftService.createDraft(request);
        }

        return draftService.createDraft(request, conversationId);
    }

    @GetMapping("/drafts")
    public List<AppointmentDraftResponse> findDraftsByConversation(
            @RequestParam(name = "conversationId") String conversationId) {

        return draftService.findByConversationId(conversationId);
    }

    @GetMapping("/drafts/{draftId}")
    public AppointmentDraftResponse findDraft(
            @PathVariable("draftId") String draftId) {

        return draftService.findById(draftId);
    }

    @PostMapping("/drafts/{draftId}/confirm")
    public AppointmentResponse confirmDraft(
            @PathVariable("draftId") String draftId) {

        return draftService.confirm(draftId);
    }

    @PostMapping("/drafts/{draftId}/cancel")
    public AppointmentDraftResponse cancelDraft(
            @PathVariable("draftId") String draftId) {

        return draftService.cancel(draftId);
    }

    @PostMapping("/{appointmentId}/cancel")
    public AppointmentResponse cancelAppointment(
            @PathVariable("appointmentId") String appointmentId,
            @RequestBody CancelAppointmentRequest request) {
        return appointmentService.cancel(appointmentId,
                request != null && Boolean.TRUE.equals(request.confirmed()));
    }

    @GetMapping("/{appointmentId}")
    public AppointmentResponse findAppointment(
            @PathVariable("appointmentId") String appointmentId) {

        return appointmentService.findById(appointmentId);
    }
}
