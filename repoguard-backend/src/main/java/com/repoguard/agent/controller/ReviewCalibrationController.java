package com.repoguard.agent.controller;

import com.repoguard.agent.common.ApiResponse;
import com.repoguard.agent.config.ApiRuntimeEnabled;
import com.repoguard.agent.dto.ReviewCalibrationQueueDto;
import com.repoguard.agent.security.RequireRole;
import com.repoguard.agent.service.ReviewCalibrationService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/config/review-calibration")
@ApiRuntimeEnabled
@RequireRole("ADMIN")
@Validated
public class ReviewCalibrationController {

    private final ReviewCalibrationService calibrationService;

    public ReviewCalibrationController(ReviewCalibrationService calibrationService) {
        this.calibrationService = calibrationService;
    }

    @GetMapping("/queue")
    public ApiResponse<ReviewCalibrationQueueDto> getReviewCalibrationQueue(
        @RequestParam @Size(max = 64) String ruleId,
        @RequestParam(defaultValue = "30") @Min(1) @Max(100) int limit,
        @RequestParam(defaultValue = "false") boolean includeIgnored
    ) {
        return ApiResponse.ok(calibrationService.getQueue(ruleId, limit, includeIgnored));
    }
}
