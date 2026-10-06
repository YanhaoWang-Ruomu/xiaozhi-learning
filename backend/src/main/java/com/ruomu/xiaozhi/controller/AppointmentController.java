package com.ruomu.xiaozhi.controller;

import com.ruomu.xiaozhi.dto.*;
import com.ruomu.xiaozhi.service.OwnedAppointmentService;
import com.ruomu.xiaozhi.security.AccountUser;
import org.springframework.security.core.Authentication;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping(value="/api/appointments", produces="application/json;charset=UTF-8")
public class AppointmentController {
    private final OwnedAppointmentService owned;
    public AppointmentController(OwnedAppointmentService owned) { this.owned = owned; }
    @PostMapping("/drafts") @ResponseStatus(HttpStatus.CREATED)
    public AppointmentDraftResponse createDraft(@RequestBody CreateAppointmentRequest request,
            @RequestParam("conversationId") String conversationId, Authentication authentication) {
        return owned.create(request, conversationId, AccountUser.require(authentication).userId());
    }
    @GetMapping("/drafts")
    public List<AppointmentDraftResponse> list(@RequestParam("conversationId") String conversationId, Authentication authentication) {
        return owned.list(conversationId, AccountUser.require(authentication).userId());
    }
    @GetMapping("/drafts/{id}")
    public AppointmentDraftResponse draft(@PathVariable("id") String id, Authentication authentication) {
        return owned.findDraft(id, AccountUser.require(authentication).userId());
    }
    @PostMapping("/drafts/{id}/confirm")
    public AppointmentResponse confirm(@PathVariable("id") String id, Authentication authentication) {
        return owned.confirm(id, AccountUser.require(authentication).userId());
    }
    @PostMapping("/drafts/{id}/cancel")
    public AppointmentDraftResponse cancelDraft(@PathVariable("id") String id, Authentication authentication) {
        return owned.cancelDraft(id, AccountUser.require(authentication).userId());
    }
    @GetMapping("/{id}")
    public AppointmentResponse appointment(@PathVariable("id") String id, Authentication authentication) {
        return owned.findAppointment(id, AccountUser.require(authentication).userId());
    }
    @PostMapping("/{id}/cancel")
    public AppointmentResponse cancel(@PathVariable("id") String id, @RequestBody CancelAppointmentRequest request, Authentication authentication) {
        return owned.cancelAppointment(id, request != null && Boolean.TRUE.equals(request.confirmed()), AccountUser.require(authentication).userId());
    }
}
