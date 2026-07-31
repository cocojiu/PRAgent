package com.repoguard.agent.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.repoguard.agent.dto.ReviewCalibrationQueueDto;
import com.repoguard.agent.dto.ReviewCalibrationVersionDto;
import com.repoguard.agent.dto.ReviewRuleQualityGateDto;
import com.repoguard.agent.service.ReviewCalibrationService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ReviewCalibrationControllerTest {

    private final ReviewCalibrationService service = (ruleId, limit, includeIgnored) -> queue(ruleId, limit);
    private final MockMvc mockMvc = MockMvcBuilders
        .standaloneSetup(new ReviewCalibrationController(service))
        .build();

    @Test
    void getsFixedVersionCalibrationQueueWithDefaultWindow() throws Exception {
        mockMvc.perform(get("/api/v1/config/review-calibration/queue")
                .queryParam("ruleId", "RG-AUTH-001"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.version.ruleId").value("RG-AUTH-001"))
            .andExpect(jsonPath("$.data.targetLabeledSamples").value(30))
            .andExpect(jsonPath("$.data.remainingToTarget").value(30))
            .andExpect(jsonPath("$.data.samples").isArray());
    }

    private ReviewCalibrationQueueDto queue(String ruleId, int limit) {
        return new ReviewCalibrationQueueDto(
            new ReviewCalibrationVersionDto(
                ruleId,
                "权限保护缺失",
                "rg-auth-001-detector-v2",
                1,
                1,
                1,
                1,
                "llm-prompt-v2",
                "review-context-v2",
                "finding-schema-v2",
                "high-risk-verifier-v1",
                "server-risk-v2",
                "observe",
                "observe",
                true,
                "fixed-version"
            ),
            limit,
            0,
            0,
            0,
            0,
            0,
            30,
            new ReviewRuleQualityGateDto(
                0,
                0,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                false,
                false,
                "INSUFFICIENT_SAMPLE",
                List.of("labeled_high_risk_samples_below_30")
            ),
            List.of()
        );
    }
}
