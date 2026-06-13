package com.repoguard.agent.dto;

/**
 * 评审详情中的 LLM 执行状态区块。
 */
public record LlmStatusDto(
    String status,
    String duration,
    String riskLevel,
    String provider,
    String model,
    Integer durationMs,
    String parseStatus,
    String fallbackReason,
    String promptSummary
) {
    public LlmStatusDto(String status, String duration, String riskLevel) {
        this(status, duration, riskLevel, null, null, null, null, null, null);
    }
}
