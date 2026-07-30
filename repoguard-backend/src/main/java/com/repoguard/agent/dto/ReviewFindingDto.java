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
    String confidence,
    String evidence,
    String impact,
    String fixExample,
    Boolean isBlocking,
    String reviewDimension,
    String feedbackStatus,
    String feedbackNote,
    String feedbackBy,
    String feedbackAt,
    String enforcementMode,
    String policyReason
) {
    public ReviewFindingDto(
        Long id,
        String severity,
        String file,
        Integer line,
        String message,
        String recommendation,
        String confidence,
        String evidence,
        String impact,
        String fixExample,
        Boolean isBlocking,
        String reviewDimension,
        String feedbackStatus,
        String feedbackNote,
        String feedbackBy,
        String feedbackAt
    ) {
        this(
            id,
            severity,
            file,
            line,
            message,
            recommendation,
            confidence,
            evidence,
            impact,
            fixExample,
            isBlocking,
            reviewDimension,
            feedbackStatus,
            feedbackNote,
            feedbackBy,
            feedbackAt,
            isBlocking != null && isBlocking ? "BLOCK" : "COMMENT",
            "legacy_finding"
        );
    }

    public ReviewFindingDto(
        String severity,
        String file,
        Integer line,
        String message,
        String recommendation
    ) {
        this(
            null,
            severity,
            file,
            line,
            message,
            recommendation,
            "LOW",
            "",
            "",
            recommendation,
            false,
            "",
            "unreviewed",
            null,
            null,
            null,
            "COMMENT",
            "legacy_finding"
        );
    }
}
