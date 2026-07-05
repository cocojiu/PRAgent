package com.repoguard.agent.dto;

import java.util.List;

public record DashboardLlmQualityResponse(
    List<LlmQualityByModelDto> byModel,
    List<LlmQualityByRepositoryDto> byRepository,
    List<LlmQualityTrendPointDto> trend
) {
}
