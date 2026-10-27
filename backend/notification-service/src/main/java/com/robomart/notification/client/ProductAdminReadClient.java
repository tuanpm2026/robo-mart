package com.robomart.notification.client;

import java.util.Map;

import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

import com.robomart.common.dto.PagedResponse;

@HttpExchange(url = "/api/v1/admin", accept = "application/json")
public interface ProductAdminReadClient {

    @GetExchange("/audit-logs")
    PagedResponse<AdminReadModels.AuditEntry> audits(@RequestParam Map<String, String> filters);
}
