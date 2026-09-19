package com.repoguard.agent.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Public evaluation run progress; source and patch content are never exposed. */
public record LlmEvaluationRunDto(
    String runId,
    String runKey,
    String status,
    int totalSamples,
    int completedSamples,
    long totalTokens,
    BigDecimal totalCost,
    Long reportId,
    String failureCode,
    LocalDateTime submittedAt,
    LocalDateTime startedAt,
    LocalDateTime finishedAt,
    Diagnostics diagnostics
) {
    public LlmEvaluationRunDto(String runId, String runKey, String status, int totalSamples,
        int completedSamples, long totalTokens, BigDecimal totalCost, Long reportId, String failureCode,
        LocalDateTime submittedAt, LocalDateTime startedAt, LocalDateTime finishedAt) {
        this(runId, runKey, status, totalSamples, completedSamples, totalTokens, totalCost, reportId,
            failureCode, submittedAt, startedAt, finishedAt, null);
    }

    /** A diagnostic never produces a promotable evaluation report. */
    public record Diagnostics(java.util.List<String> sampleIds, java.util.List<SampleDiagnostic> samples) {
        public Diagnostics {
            sampleIds = java.util.List.copyOf(sampleIds);
            samples = java.util.List.copyOf(samples);
        }
    }

    public record SampleDiagnostic(String sampleId, String status, String failureCode,
        long totalTokens, BigDecimal estimatedCost, String usageSource, String costSource) { }
}
