package com.repoguard.agent.controller;

import com.repoguard.agent.common.ApiResponse;
import com.repoguard.agent.config.ApiRuntimeEnabled;
import com.repoguard.agent.dto.DeclarativeRuleDryRunRequest;
import com.repoguard.agent.dto.DeclarativeRuleDryRunResponse;
import com.repoguard.agent.review.config.DeclarativeRuleDryRunService;
import com.repoguard.agent.security.RequireRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/config/declarative-rules")
@ApiRuntimeEnabled
@RequireRole({"ADMIN", "PLATFORM_ADMIN", "RULE_ADMIN"})
public class DeclarativeRuleController {

    private final DeclarativeRuleDryRunService dryRunService;

    public DeclarativeRuleController(DeclarativeRuleDryRunService dryRunService) {
        this.dryRunService = dryRunService;
    }

    @PostMapping("/{id}/dry-run")
    public ApiResponse<DeclarativeRuleDryRunResponse> dryRun(
        @PathVariable @Size(max = 64) String id,
        @Valid @RequestBody DeclarativeRuleDryRunRequest request
    ) {
        return ApiResponse.ok(dryRunService.run(id, request));
    }
}
