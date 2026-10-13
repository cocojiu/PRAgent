package com.repoguard.agent.controller;

import com.repoguard.agent.common.ApiResponse;
import com.repoguard.agent.config.ApiRuntimeEnabled;
import com.repoguard.agent.dto.LlmUsageAccountingDto.PendingUsage;
import com.repoguard.agent.dto.LlmUsageAccountingDto.ReconciliationRequest;
import com.repoguard.agent.dto.LlmUsageAccountingDto.ReconciledUsage;
import com.repoguard.agent.review.LlmUsageBudgetLedger;
import com.repoguard.agent.security.RequireRole;
import com.repoguard.agent.web.RequestAuthentication;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/config/llm-usage")
@ApiRuntimeEnabled
@RequireRole({"ADMIN", "PLATFORM_ADMIN"})
@Validated
public class LlmUsageAccountingController {
    private final LlmUsageBudgetLedger ledger;

    public LlmUsageAccountingController(LlmUsageBudgetLedger ledger) { this.ledger = ledger; }

    @GetMapping("/pending")
    public ApiResponse<List<PendingUsage>> pending(@RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return ApiResponse.ok(ledger.pendingCalls(limit));
    }

    @PostMapping("/reconcile")
    public ApiResponse<ReconciledUsage> reconcile(@Valid @RequestBody ReconciliationRequest request,
        HttpServletRequest servletRequest) {
        return ApiResponse.ok(ledger.reconcile(request, RequestAuthentication.require(servletRequest).username()));
    }
}
