package com.ruomu.xiaozhi.controller;

import com.ruomu.xiaozhi.dto.AppointmentCatalogResponse;
import com.ruomu.xiaozhi.service.AppointmentCatalogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AppointmentCatalogController {

    private final AppointmentCatalogService catalogService;

    public AppointmentCatalogController(
            AppointmentCatalogService catalogService) {

        this.catalogService = catalogService;
    }

    @GetMapping("/api/appointment/catalog")
    public AppointmentCatalogResponse getCatalog(
            @RequestParam(name = "hospitalId") String hospitalId,
            @RequestParam(name = "department") String department) {

        return catalogService.findCatalog(
                hospitalId,
                department
        );
    }
}