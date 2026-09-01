package com.repoguard.agent.dto;

import java.util.List;

public record LlmModelReleaseCenterDto(
    String configuredProvider,
    String configuredModel,
    LlmModelReleaseDto activeRelease,
    LlmModelReleaseDto canaryRelease,
    List<LlmModelReleaseDto> releases,
    List<LlmQualityByModelDto> modelComparison,
    LlmModelBudgetDto monthlyBudget,
    String recommendedAction
) {
}
