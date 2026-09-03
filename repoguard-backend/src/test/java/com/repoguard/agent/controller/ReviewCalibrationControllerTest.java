package com.repoguard.agent.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.repoguard.agent.dto.ReviewCalibrationQueueDto;
import com.repoguard.agent.dto.ReviewCalibrationVersionDto;
import com.repoguard.agent.dto.ReviewRuleQualityGateDto;
import com.repoguard.agent.dto.LlmModelReleaseDto.LlmModelReleaseAuditDto;
import com.repoguard.agent.dto.LlmModelReleaseDto.LlmModelReleaseAuditExportDto;
import com.repoguard.agent.dto.LlmModelReleaseDto.LlmModelReleaseAuditVerificationDto;
import com.repoguard.agent.dto.LlmModelReleaseMetricDto;
import com.repoguard.agent.dto.PageResponse;
import com.repoguard.agent.review.quality.LlmModelReleaseService;
import com.repoguard.agent.review.quality.LlmModelReleaseMetricsService;
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

    @Test
    void listsCollectedRuntimeMetricsWithBoundedQueryParameters() throws Exception {
        LlmModelReleaseMetricsService metricsService = org.mockito.Mockito.mock(LlmModelReleaseMetricsService.class);
        org.mockito.Mockito.when(metricsService.collectAndList("release-next", 7, 20)).thenReturn(List.of(
            new LlmModelReleaseMetricDto(
                8L, 7L, "release-next", "openai", "gpt-next",
                java.time.LocalDateTime.of(2026, 9, 3, 1, 0), java.time.LocalDateTime.of(2026, 9, 3, 2, 0),
                12L, 1200L, BigDecimal.valueOf(0.12), 1200L, 0L, 0L, 0L,
                "NORMAL", List.of(), "NONE", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                null, null
            )
        ));
        MockMvc metricsMvc = MockMvcBuilders.standaloneSetup(
            new ReviewCalibrationController(service, null, metricsService)
        ).build();

        metricsMvc.perform(get("/api/v1/config/review-calibration/release-center/runtime-metrics")
                .queryParam("releaseKey", "release-next")
                .queryParam("days", "7")
                .queryParam("limit", "20"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].releaseKey").value("release-next"))
            .andExpect(jsonPath("$.data[0].sampleCount").value(12));

        org.mockito.Mockito.verify(metricsService).collectAndList("release-next", 7, 20);
    }

    @Test
    void listsVerifiesAndExportsReleaseAudits() throws Exception {
        LlmModelReleaseService releaseService = org.mockito.Mockito.mock(LlmModelReleaseService.class);
        LlmModelReleaseAuditDto audit = new LlmModelReleaseAuditDto(
            91L, 7L, "release-7", "PROMOTE", "SHADOW", "CANARY", 25,
            "operator", "reason", "{\"before\":{}}", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            java.time.LocalDateTime.of(2026, 9, 3, 0, 0), true, "VALID");
        org.mockito.Mockito.when(releaseService.listReleaseAudits(
            org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq("release-7"),
            org.mockito.ArgumentMatchers.eq("operator"), org.mockito.ArgumentMatchers.eq("PROMOTE"),
            org.mockito.ArgumentMatchers.eq(null), org.mockito.ArgumentMatchers.eq(null),
            org.mockito.ArgumentMatchers.eq(1), org.mockito.ArgumentMatchers.eq(20)))
            .thenReturn(new PageResponse<>(List.of(audit), 1L));
        org.mockito.Mockito.when(releaseService.verifyReleaseAudit(91L)).thenReturn(
            new LlmModelReleaseAuditVerificationDto(91L, 7L, "release-7", audit.eventHash(), audit.eventHash(), true, "VALID"));
        org.mockito.Mockito.when(releaseService.exportReleaseAudits(
            org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq("release-7"),
            org.mockito.ArgumentMatchers.eq(null), org.mockito.ArgumentMatchers.eq(null),
            org.mockito.ArgumentMatchers.eq(null), org.mockito.ArgumentMatchers.eq(null), org.mockito.ArgumentMatchers.eq("csv")))
            .thenReturn(new LlmModelReleaseAuditExportDto("csv", 1L, audit.eventHash(), "id,releaseId\n91,7\n"));
        MockMvc auditMvc = MockMvcBuilders.standaloneSetup(
            new ReviewCalibrationController(service, releaseService, null)
        ).build();

        auditMvc.perform(get("/api/v1/config/review-calibration/release-center/audits")
                .queryParam("releaseId", "7")
                .queryParam("releaseKey", "release-7")
                .queryParam("operator", "operator")
                .queryParam("action", "PROMOTE"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].hashValid").value(true))
            .andExpect(jsonPath("$.data.total").value(1));
        auditMvc.perform(get("/api/v1/config/review-calibration/release-center/audits/91/verify"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.valid").value(true));
        auditMvc.perform(get("/api/v1/config/review-calibration/release-center/audits/export")
                .queryParam("releaseId", "7")
                .queryParam("releaseKey", "release-7")
                .queryParam("format", "csv"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.format").value("csv"))
            .andExpect(jsonPath("$.data.recordCount").value(1));

        org.mockito.Mockito.verify(releaseService).verifyReleaseAudit(91L);
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
