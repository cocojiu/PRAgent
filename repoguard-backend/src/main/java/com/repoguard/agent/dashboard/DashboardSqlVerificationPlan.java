package com.repoguard.agent.dashboard;

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

    public List<ExplainObservation> explainObservations() {
        return List.of(
            new ExplainObservation(
                "selectMetricStat",
                List.of("idx_review_task_created_at", "idx_review_task_dashboard_created_risk"),
                List.of("range"),
                "rows should stay bounded by the selected dashboard time window.",
                List.of("key should not be null", "type should not degrade to ALL")
            ),
            new ExplainObservation(
                "selectRiskLevelCounts",
                List.of("idx_review_task_dashboard_created_risk"),
                List.of("range"),
                "rows should stay bounded by created_at before grouping by risk_level.",
                List.of("key should prefer the created_at/risk_level composite index", "type should not degrade to ALL")
            ),
            new ExplainObservation(
                "selectReviewTrendCounts",
                List.of("idx_review_task_created_at"),
                List.of("range"),
                "rows should stay bounded by the trend window even when date_format grouping uses a derived expression.",
                List.of("key should not be null", "Using temporary is acceptable only when rows remains window-bounded")
            ),
            new ExplainObservation(
                "selectRuleHitCounts",
                List.of("idx_review_task_created_at", "idx_review_finding_task_category_rule"),
                List.of("range", "ref", "eq_ref"),
                "rows should stay bounded by the review_task time window and task_id/category finding join.",
                List.of("review_finding should not be scanned as ALL", "join key should include task_id/category when possible")
            ),
            new ExplainObservation(
                "selectRecentHighRiskReviews",
                List.of("idx_review_task_risk_created", "idx_review_task_dashboard_created_risk", "idx_review_finding_task_category_rule"),
                List.of("range", "ref", "eq_ref"),
                "rows should stay bounded by high/critical risk plus the dashboard time window before the limit is applied.",
                List.of("review_task key should include risk_level or created_at", "review_finding join should not scan all findings")
            ),
            new ExplainObservation(
                "selectLlmQualityTrendCounts",
                List.of("idx_review_task_dashboard_created_llm_model"),
                List.of("range"),
                "rows should stay bounded by created_at because llm_status uses exclusion filters instead of equality.",
                List.of("key should prefer the created_at/llm_status composite index", "type should not degrade to ALL")
            ),
            new ExplainObservation(
                "selectLlmQualityByModelStats",
                List.of("idx_review_task_dashboard_created_llm_model", "idx_review_finding_task_category_rule"),
                List.of("range", "ref", "eq_ref"),
                "derived task and feedback rows should stay bounded by created_at before model grouping and task_id finding joins.",
                List.of("derived table filesort is acceptable only after window filtering", "review_finding join should use task_id/category")
            ),
            new ExplainObservation(
                "selectLlmQualityByRepositoryStats",
                List.of("idx_review_task_dashboard_created_llm_repo", "idx_review_finding_task_category_rule"),
                List.of("range", "ref", "eq_ref"),
                "derived task and feedback rows should stay bounded by created_at before repository grouping and task_id finding joins.",
                List.of("derived table filesort is acceptable only after window filtering", "review_finding join should use task_id/category")
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

    public record ExplainObservation(
        String mapperMethod,
        List<String> keyCandidates,
        List<String> acceptableAccessTypes,
        String rowsExpectation,
        List<String> extraWatchItems
    ) {
    }
}
