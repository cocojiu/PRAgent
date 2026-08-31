package com.repoguard.agent.controller;

import com.repoguard.agent.common.ApiResponse;
import com.repoguard.agent.dto.PageResponse;
import com.repoguard.agent.dto.ReviewAttemptChangedFileDto;
import com.repoguard.agent.dto.ReviewAttemptFindingDto;
import com.repoguard.agent.review.execution.ReviewExecutionAttemptQueryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Independent cursor endpoints for the potentially large attempt collections. */
@Validated
@RestController
@RequestMapping("/api/v1/reviews/{taskId}/attempts/{attemptId}")
public class ReviewExecutionAttemptCollectionController {

    private final ReviewExecutionAttemptQueryService queryService;

    public ReviewExecutionAttemptCollectionController(ReviewExecutionAttemptQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/changed-files")
    public ApiResponse<PageResponse<ReviewAttemptChangedFileDto>> changedFiles(
        @PathVariable @Min(1) Long taskId,
        @PathVariable @Min(1) Long attemptId,
        @RequestParam(required = false) @Min(1) Long cursor,
        @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit
    ) {
        return ApiResponse.ok(queryService.listChangedFiles(taskId, attemptId, cursor, limit));
    }

    @GetMapping("/findings")
    public ApiResponse<PageResponse<ReviewAttemptFindingDto>> findings(
        @PathVariable @Min(1) Long taskId,
        @PathVariable @Min(1) Long attemptId,
        @RequestParam(required = false) @Min(1) Long cursor,
        @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit
    ) {
        return ApiResponse.ok(queryService.listFindings(taskId, attemptId, cursor, limit));
    }
}
