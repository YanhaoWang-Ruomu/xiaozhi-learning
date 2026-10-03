package com.ruomu.xiaozhi.controller;

import com.ruomu.xiaozhi.dto.AppointmentResponse;
import com.ruomu.xiaozhi.dto.CreateAppointmentRequest;
import com.ruomu.xiaozhi.service.AppointmentService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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

    public AppointmentController(AppointmentService appointmentService) {
        this.appointmentService = appointmentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AppointmentResponse create(
            @RequestBody CreateAppointmentRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {

        return appointmentService.create(request, idempotencyKey);
    }

    @GetMapping("/{appointmentId}")
    public AppointmentResponse findById(
            @PathVariable("appointmentId") String appointmentId) {

        return appointmentService.findById(appointmentId);
    }
}