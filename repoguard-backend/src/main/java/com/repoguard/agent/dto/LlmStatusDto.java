package com.repoguard.agent.dto;

/**
 * 评审详情中的 LLM 执行状态区块。
 */
public record LlmStatusDto(
    String status,
    String duration,
    String riskLevel
) {
}
