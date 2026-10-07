package com.ruomu.xiaozhi.controller;
import com.ruomu.xiaozhi.service.AppointmentWorkflowService;
import com.ruomu.xiaozhi.security.AccountUser;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/workflows")
public class AppointmentWorkflowController {
    private final AppointmentWorkflowService service;
    public AppointmentWorkflowController(AppointmentWorkflowService service){this.service=service;}
    public record Create(String conversationId){}
    public record Action(long version,boolean confirmed){}
    private String user(Authentication a){return AccountUser.require(a).userId();}
    @PostMapping public Object create(@RequestBody Create c,Authentication a){return service.create(c.conversationId(),user(a));}
    @GetMapping("/{id}") public Object get(@PathVariable String id,Authentication a){return service.get(id,user(a));}
    @PutMapping("/{id}/requirements") public Object requirements(@PathVariable String id,@RequestBody AppointmentWorkflowService.Requirements r,Authentication a){return service.requirements(id,user(a),r);}
    @GetMapping("/{id}/sessions") public Object query(@PathVariable String id,Authentication a){return service.query(id,user(a));}
    @PostMapping("/{id}/draft") public Object draft(@PathVariable String id,@RequestBody Action r,Authentication a){return service.draft(id,user(a),r.version(),r.confirmed());}
    @PostMapping("/{id}/confirm") public Object confirm(@PathVariable String id,@RequestBody Action r,Authentication a){return service.confirm(id,user(a),r.confirmed());}
    @PostMapping("/{id}/cancel") public Object cancel(@PathVariable String id,@RequestBody Action r,Authentication a){return service.cancel(id,user(a),r.confirmed());}
}
