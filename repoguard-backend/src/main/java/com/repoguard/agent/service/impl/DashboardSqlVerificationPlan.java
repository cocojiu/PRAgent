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
                List.of("idx_review_task_created_at", "idx_review_task_dashboard_created_risk")
            ),
            new QueryAssumption(
                "selectRiskLevelCounts",
                "7-day risk distribution grouped by review_task.risk_level",
                List.of("idx_review_task_dashboard_created_risk")
            ),
            new QueryAssumption(
                "selectReviewTrendCounts",
                "7-day daily review trend grouped by review_task.created_at",
                List.of("idx_review_task_created_at")
            ),
            new QueryAssumption(
                "selectRuleHitCounts",
                "7-day rule hit aggregate joining review_finding to review_task by task_id",
                List.of("idx_review_task_created_at", "idx_review_finding_task_category_rule")
            ),
            new QueryAssumption(
                "selectRecentHighRiskReviews",
                "7-day high-risk task list ordered by review_task.created_at desc",
                List.of("idx_review_task_risk_created", "idx_review_finding_task_category_rule")
            ),
            new QueryAssumption(
                "selectLlmQualityTrendCounts",
                "7/30/90-day LLM quality trend bounded by review_task.created_at and filtered by llm_status",
                List.of("idx_review_task_dashboard_created_llm_model")
            ),
            new QueryAssumption(
                "selectLlmQualityByModelStats",
                "7-day LLM model breakdown with feedback join by task_id",
                List.of("idx_review_task_dashboard_created_llm_model", "idx_review_finding_task_category_rule")
            ),
            new QueryAssumption(
                "selectLlmQualityByRepositoryStats",
                "7-day LLM repository breakdown with feedback join by task_id",
                List.of("idx_review_task_dashboard_created_llm_repo", "idx_review_finding_task_category_rule")
            )
        );
    }

    public List<IndexAlignment> indexAlignments() {
        return List.of(
            new IndexAlignment(
                "idx_review_task_dashboard_created_risk",
                List.of("created_at", "risk_level"),
                "Dashboard review task aggregates first constrain the time window, then derive or group by risk level."
            ),
            new IndexAlignment(
                "idx_review_task_dashboard_created_llm_model",
                List.of("created_at", "llm_status", "llm_provider", "llm_model"),
                "LLM quality model queries first constrain the time window; llm_status uses exclusion filters rather than equality."
            ),
            new IndexAlignment(
                "idx_review_task_dashboard_created_llm_repo",
                List.of("created_at", "llm_status", "organization", "repository"),
                "LLM repository queries first constrain the time window, then group by repository identity."
            ),
            new IndexAlignment(
                "idx_review_finding_task_category_rule",
                List.of("task_id", "category", "rule_id"),
                "Dashboard finding joins enter by task_id and apply FINDING category before rule aggregation."
            )
        );
    }

    public record QueryAssumption(
        String mapperMethod,
        String verificationScope,
        List<String> supportingIndexes
    ) {
    }

    public record IndexAlignment(
        String indexName,
        List<String> leadingColumns,
        String reason
    ) {
    }
}
