package com.repoguard.agent.controller;

import com.repoguard.agent.common.ApiResponse;
import com.repoguard.agent.dto.ReviewAttemptComparisonDto;
import com.repoguard.agent.review.execution.ReviewFindingComparisonService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only, paged comparison for a candidate attempt and its previous attempt. */
@Validated
@RestController
@RequestMapping("/api/v1/reviews/{taskId}/attempts/{candidateAttemptId}/comparison")
public class ReviewExecutionAttemptComparisonController {

    private final ReviewFindingComparisonService comparisonService;

    public ReviewExecutionAttemptComparisonController(ReviewFindingComparisonService comparisonService) {
        this.comparisonService = comparisonService;
    }

    @GetMapping
    public ApiResponse<ReviewAttemptComparisonDto> compare(
        @PathVariable @Min(1) Long taskId,
        @PathVariable @Min(1) Long candidateAttemptId,
        @RequestParam(required = false) @Min(1) Long baselineAttemptId,
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "50") @Min(1) @Max(100) int pageSize
    ) {
        return ApiResponse.ok(comparisonService.compare(
            taskId, baselineAttemptId, candidateAttemptId, page, pageSize
        ));
    }
}
