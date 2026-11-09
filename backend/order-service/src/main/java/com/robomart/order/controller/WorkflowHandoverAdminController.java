package com.robomart.order.controller;

import com.robomart.order.workflow.OrderWorkflowCoordinator;

import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/orders")
public class WorkflowHandoverAdminController {
    private final OrderWorkflowCoordinator workflow;

    public WorkflowHandoverAdminController(OrderWorkflowCoordinator workflow) {
        this.workflow = workflow;
    }

    @PostMapping("/{id}/workflow/adopt")
    public Map<String, String> adopt(
            @PathVariable long id, @RequestBody Handover body, JwtAuthenticationToken actor) {
        return Map.of("sagaId", workflow.adoptConfirmed(id, actor.getName(), body.reason()));
    }

    public record Handover(String reason) {}
}
