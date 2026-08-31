package com.repoguard.agent.controller;

import com.repoguard.agent.common.ApiResponse;
import com.repoguard.agent.dto.ReviewExecutionAttemptDto;
import com.repoguard.agent.dto.ReviewExecutionAttemptResultDto;
import com.repoguard.agent.review.execution.ReviewExecutionAttemptQueryService;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/reviews/{taskId}/attempts")
public class ReviewExecutionAttemptController {

    private final ReviewExecutionAttemptQueryService queryService;

    public ReviewExecutionAttemptController(ReviewExecutionAttemptQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public ApiResponse<List<ReviewExecutionAttemptDto>> list(@PathVariable @Min(1) Long taskId) {
        return ApiResponse.ok(queryService.list(taskId));
    }

    @GetMapping("/{attemptId}")
    public ApiResponse<ReviewExecutionAttemptResultDto> getResult(
        @PathVariable @Min(1) Long taskId,
        @PathVariable @Min(1) Long attemptId,
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "50") @Min(1) @Max(100) int pageSize
    ) {
        return ApiResponse.ok(queryService.getResult(taskId, attemptId, page, pageSize));
    }

}
