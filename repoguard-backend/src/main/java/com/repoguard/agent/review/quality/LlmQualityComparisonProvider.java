package com.repoguard.agent.review.quality;

import com.repoguard.agent.dto.DashboardLlmQualityResponse;

/**
 * Port used by review orchestration to consume aggregate LLM quality comparisons without
 * coupling the review boundary to the dashboard implementation package.
 */
@FunctionalInterface
public interface LlmQualityComparisonProvider {

    DashboardLlmQualityResponse getLlmQuality(Integer trendDays);
}
