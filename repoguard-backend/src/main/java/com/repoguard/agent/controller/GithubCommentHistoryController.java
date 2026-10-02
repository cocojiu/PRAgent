package com.repoguard.agent.controller;

import com.repoguard.agent.common.ApiResponse;
import com.repoguard.agent.dto.GithubCommentPublicationHistoryResponse;
import com.repoguard.agent.dto.GithubCommentPublicationItemsResponse;
import com.repoguard.agent.service.GithubCommentHistoryQueryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Bounded history reads; the legacy full-history endpoint remains available for application rollback. */
@RestController
@Validated
@RequestMapping("/api/v1/reviews/{taskId}/github-comments/publications")
public class GithubCommentHistoryController {
    private final GithubCommentHistoryQueryService history;

    public GithubCommentHistoryController(GithubCommentHistoryQueryService history) {
        this.history = history;
    }

    @GetMapping("/batches")
    public ApiResponse<GithubCommentPublicationHistoryResponse> getBatches(
        @PathVariable @Min(1) Long taskId,
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
        @RequestParam(required = false) @Size(max = 32) String status
    ) {
        return ApiResponse.ok(history.getPublicationBatches(taskId, page, pageSize, status));
    }

    @GetMapping("/{batchId}/items")
    public ApiResponse<GithubCommentPublicationItemsResponse> getItems(
        @PathVariable @Min(1) Long taskId,
        @PathVariable @Min(1) Long batchId,
        @RequestParam(defaultValue = "0") @Min(0) long afterId,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize
    ) {
        return ApiResponse.ok(history.getPublicationItems(taskId, batchId, afterId, pageSize));
    }
}
