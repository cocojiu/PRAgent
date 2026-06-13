package com.repoguard.agent.dto;

import java.util.List;

public record PrRiskProfileDto(
    Integer score,
    String level,
    String summary,
    Boolean recommendHumanReview,
    String humanReviewReason,
    List<String> signals,
    List<PrRiskFileDto> highRiskFiles
) {
}
