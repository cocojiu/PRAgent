package com.repoguard.agent.dto;

/**
 * 从审查问题中提取的缺失测试建议。
 */
public record MissingTestDto(
    String file,
    String method,
    String type,
    String suggestion
) {
}
