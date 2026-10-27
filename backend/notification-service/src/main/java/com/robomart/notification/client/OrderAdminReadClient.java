package com.robomart.notification.client;

import java.util.Map;
import java.util.List;

import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

import com.robomart.common.dto.ApiResponse;
import com.robomart.common.dto.PagedResponse;

@HttpExchange(url = "/api/v1/admin/orders", accept = "application/json")
public interface OrderAdminReadClient {
    @GetExchange("/reconciliation-summary")
    ApiResponse<List<AdminReadModels.OrderSummary>> reconciliation();

    @GetExchange("/audit-logs")
    PagedResponse<AdminReadModels.AuditEntry> audits(@RequestParam Map<String, String> filters);
}
