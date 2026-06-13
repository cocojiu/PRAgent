package com.repoguard.agent.dto;

/**
 * 返回给前端的代码审查问题，包含严重程度和修复建议。
 */
public record ReviewFindingDto(
    Long id,
    String severity,
    String file,
    Integer line,
    String message,
    String recommendation,
    String feedbackStatus,
    String feedbackNote,
    String feedbackBy,
    String feedbackAt
) {
    public ReviewFindingDto(
        String severity,
        String file,
        Integer line,
        String message,
        String recommendation
    ) {
        this(null, severity, file, line, message, recommendation, "unreviewed", null, null, null);
    }
}
