package com.repoguard.agent.dto;

/**
 * 返回给前端的代码审查问题，包含严重程度和修复建议。
 */
public record ReviewFindingDto(
    String severity,
    String file,
    Integer line,
    String message,
    String recommendation
) {
}
