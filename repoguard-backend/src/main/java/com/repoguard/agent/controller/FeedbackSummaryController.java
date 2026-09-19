package com.repoguard.agent.controller;

import com.repoguard.agent.common.ApiResponse;
import com.repoguard.agent.config.ApiRuntimeEnabled;
import com.repoguard.agent.dashboard.FeedbackSummaryService;
import com.repoguard.agent.dashboard.FeedbackSummaryService.FeedbackSummary;
import com.repoguard.agent.security.RequireRole;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ApiRuntimeEnabled
@RequireRole("ADMIN")
@RequestMapping("/api/v1/config/feedback-summary")
public class FeedbackSummaryController {
    private final FeedbackSummaryService feedback;
    public FeedbackSummaryController(FeedbackSummaryService feedback) { this.feedback = feedback; }
    @GetMapping
    public ApiResponse<FeedbackSummary> summary() { return ApiResponse.ok(feedback.summary()); }
}
