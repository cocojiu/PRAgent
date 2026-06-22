package com.repoguard.agent.service.impl;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class DashboardSqlVerificationPlan {

    public List<QueryAssumption> queryAssumptions() {
        return List.of(
            new QueryAssumption(
                "selectMetricStat",
                "7-day review task metric aggregate bounded by review_task.created_at",
                List.of("idx_review_task_created_at", "idx_review_task_risk_created", "idx_review_task_status_created")
            ),
            new QueryAssumption(
                "selectRiskLevelCounts",
                "7-day risk distribution grouped by review_task.risk_level",
                List.of("idx_review_task_created_at", "idx_review_task_risk_created")
            ),
            new QueryAssumption(
                "selectReviewTrendCounts",
                "7-day daily review trend grouped by review_task.created_at",
                List.of("idx_review_task_created_at")
            ),
            new QueryAssumption(
                "selectRuleHitCounts",
                "7-day rule hit aggregate joining review_finding to review_task by task_id",
                List.of("idx_review_finding_category", "idx_review_finding_task", "idx_review_task_created_at")
            ),
            new QueryAssumption(
                "selectRecentHighRiskReviews",
                "7-day high-risk task list ordered by review_task.created_at desc",
                List.of("idx_review_task_risk_created", "idx_review_finding_task")
            ),
            new QueryAssumption(
                "selectLlmQualityTrendCounts",
                "7/30/90-day LLM quality trend bounded by review_task.created_at and filtered by llm_status",
                List.of("idx_review_task_llm_quality", "idx_review_task_llm_model_window")
            ),
            new QueryAssumption(
                "selectLlmQualityByModelStats",
                "7-day LLM model breakdown with feedback join by task_id",
                List.of("idx_review_task_llm_model_window", "idx_review_finding_task", "idx_review_finding_category")
            ),
            new QueryAssumption(
                "selectLlmQualityByRepositoryStats",
                "7-day LLM repository breakdown with feedback join by task_id",
                List.of("idx_review_task_llm_repository_window", "idx_review_finding_task", "idx_review_finding_category")
            )
        );
    }

    public record QueryAssumption(
        String mapperMethod,
        String verificationScope,
        List<String> supportingIndexes
    ) {
    }
}
