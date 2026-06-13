package com.repoguard.agent.dto;

import java.util.List;

public record PrRiskFileDto(
    String file,
    String changeType,
    Integer additions,
    Integer deletions,
    Integer findingCount,
    Integer score,
    List<String> reasons
) {
}
