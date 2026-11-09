package com.robomart.saga.transport;

import com.robomart.saga.SagaStore;

import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/sagas")
public class SagaAdminController {
    private final SagaStore store;

    public SagaAdminController(SagaStore store) {
        this.store = store;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return store.list();
    }

    @PostMapping("/{id}/resume")
    public ResponseEntity<Void> resume(
            @PathVariable UUID id, @RequestBody Resume request, JwtAuthenticationToken actor) {
        store.resume(id, actor.getName(), request.reason());
        return ResponseEntity.accepted().build();
    }

    public record Resume(String reason) {}
}
