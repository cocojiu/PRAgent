package com.repoguard.agent.dashboard;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class DashboardSqlVerificationPlan {

    public List<QueryAssumption> queryAssumptions() {
        return List.of(
            new QueryAssumption(
                "selectLatestReviewTaskDate",
                "latest dashboard review fallback date resolved from review_task.created_at",
                List.of("idx_review_task_created_at")
            ),
            new QueryAssumption(
                "selectMetricStat",
                "7-day review task metric aggregate bounded by review_task.created_at",
                List.of("idx_review_task_created_at", "idx_review_task_dashboard_created_risk_norm")
            ),
            new QueryAssumption(
                "selectRiskLevelCounts",
                "7-day risk distribution grouped by review_task.risk_bucket_norm",
                List.of("idx_review_task_dashboard_created_risk_norm")
            ),
            new QueryAssumption(
                "selectReviewTrendCounts",
                "7-day daily review trend grouped by review_task.created_date",
                List.of("idx_review_task_dashboard_created_day")
            ),
            new QueryAssumption(
                "selectRuleHitCounts",
                "7-day rule hit aggregate joining review_finding to review_task by task_id",
                List.of("idx_review_task_created_at", "idx_review_finding_task_category_rule")
            ),
            new QueryAssumption(
                "selectRecentHighRiskReviews",
                "7-day high-risk task list ordered by review_task.created_at desc",
                List.of("idx_review_task_dashboard_created_risk_norm", "idx_review_finding_task_category_rule")
            ),
            new QueryAssumption(
                "selectLlmQualityTrendCounts",
                "7/30/90-day LLM quality trend bounded by review_task.created_at and filtered by llm_status_norm",
                List.of("idx_review_task_dashboard_created_llm_model_norm")
            ),
            new QueryAssumption(
                "selectLlmQualityByModelStats",
                "7-day LLM model breakdown with feedback join by task_id",
                List.of("idx_review_task_dashboard_created_llm_model_norm", "idx_review_finding_task_category_feedback_norm")
            ),
            new QueryAssumption(
                "selectLlmQualityByRepositoryStats",
                "7-day LLM repository breakdown with feedback join by task_id",
                List.of("idx_review_task_dashboard_created_llm_repo_norm", "idx_review_finding_task_category_feedback_norm")
            )
        );
    }

    public List<IndexAlignment> indexAlignments() {
        return List.of(
            new IndexAlignment(
                "idx_review_task_created_at",
                List.of("created_at"),
                "Latest review fallback and dashboard windows depend on a direct created_at access path."
            ),
            new IndexAlignment(
                "idx_review_task_dashboard_created_risk_norm",
                List.of("created_at", "risk_bucket_norm", "risk_level_norm", "status_norm"),
                "Dashboard review task aggregates first constrain the time window, then group or filter on generated risk/status columns."
            ),
            new IndexAlignment(
                "idx_review_task_dashboard_created_day",
                List.of("created_at", "created_date"),
                "Dashboard trend queries constrain the time window and group by the generated created_date column."
            ),
            new IndexAlignment(
                "idx_review_task_dashboard_created_llm_model_norm",
                List.of("created_at", "llm_status_norm", "llm_parse_status_norm", "llm_model_label"),
                "LLM quality model queries first constrain the time window, then use generated status and model label columns."
            ),
            new IndexAlignment(
                "idx_review_task_dashboard_created_llm_repo_norm",
                List.of("created_at", "llm_status_norm", "llm_parse_status_norm", "repository_label"),
                "LLM repository queries first constrain the time window, then group by the generated repository label."
            ),
            new IndexAlignment(
                "idx_review_finding_task_category_rule",
                List.of("task_id", "category", "rule_id"),
                "Dashboard finding joins enter by task_id and apply FINDING category before rule aggregation."
            ),
            new IndexAlignment(
                "idx_review_finding_task_category_feedback_norm",
                List.of("task_id", "category", "feedback_status_norm"),
                "Dashboard feedback joins enter by task_id/category and aggregate by the generated feedback status."
            )
        );
    }

    public List<ExplainObservation> explainObservations() {
        return List.of(
            new ExplainObservation(
                "selectLatestReviewTaskDate",
                List.of("idx_review_task_created_at"),
                List.of("range", "ref", "const"),
                "rows should stay bounded by the created_at index when resolving the latest review date.",
                List.of("key should not be null", "type should not degrade to ALL on large review_task tables")
            ),
            new ExplainObservation(
                "selectMetricStat",
                List.of("idx_review_task_created_at", "idx_review_task_dashboard_created_risk_norm"),
                List.of("range"),
                "rows should stay bounded by the selected dashboard time window.",
                List.of("key should not be null", "type should not degrade to ALL")
            ),
            new ExplainObservation(
                "selectRiskLevelCounts",
                List.of("idx_review_task_dashboard_created_risk_norm"),
                List.of("range"),
                "rows should stay bounded by created_at before grouping by risk_bucket_norm.",
                List.of("key should prefer the created_at/risk generated-column index", "type should not degrade to ALL")
            ),
            new ExplainObservation(
                "selectReviewTrendCounts",
                List.of("idx_review_task_dashboard_created_day", "idx_review_task_created_at"),
                List.of("range"),
                "rows should stay bounded by the trend window before grouping by the generated created_date.",
                List.of("key should not be null", "grouping should not apply date_format directly to created_at")
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
                List.of("idx_review_task_dashboard_created_risk_norm", "idx_review_finding_task_category_rule"),
                List.of("range", "ref", "eq_ref"),
                "rows should stay bounded by high/critical risk plus the dashboard time window before the limit is applied.",
                List.of("review_task key should include generated risk columns or created_at", "review_finding join should not scan all findings")
            ),
            new ExplainObservation(
                "selectLlmQualityTrendCounts",
                List.of("idx_review_task_dashboard_created_llm_model_norm"),
                List.of("range"),
                "rows should stay bounded by created_at because llm_status_norm uses exclusion filters instead of equality.",
                List.of("key should prefer the created_at/llm generated-column index", "type should not degrade to ALL")
            ),
            new ExplainObservation(
                "selectLlmQualityByModelStats",
                List.of("idx_review_task_dashboard_created_llm_model_norm", "idx_review_finding_task_category_feedback_norm"),
                List.of("range", "ref", "eq_ref"),
                "derived task and feedback rows should stay bounded by created_at before model grouping and task_id finding joins.",
                List.of("derived table filesort is acceptable only after window filtering", "review_finding join should use task_id/category")
            ),
            new ExplainObservation(
                "selectLlmQualityByRepositoryStats",
                List.of("idx_review_task_dashboard_created_llm_repo_norm", "idx_review_finding_task_category_feedback_norm"),
                List.of("range", "ref", "eq_ref"),
                "derived task and feedback rows should stay bounded by created_at before repository grouping and task_id finding joins.",
                List.of("derived table filesort is acceptable only after window filtering", "review_finding join should use task_id/category")
            )
        );
    }

    public List<ExplainTableExpectation> explainTableExpectations() {
        return List.of(
            new ExplainTableExpectation(
                "selectLatestReviewTaskDate",
                "review_task",
                "",
                List.of("idx_review_task_created_at"),
                List.of("range", "ref", "const"),
                "review_task rows should stay bounded by the created_at access path.",
                List.of("review_task key should not be null", "review_task type should not be ALL")
            ),
            new ExplainTableExpectation(
                "selectMetricStat",
                "review_task",
                "",
                List.of("idx_review_task_created_at", "idx_review_task_dashboard_created_risk_norm"),
                List.of("range"),
                "review_task rows should stay bounded by the dashboard time window.",
                List.of("review_task key should not be null", "review_task type should not be ALL")
            ),
            new ExplainTableExpectation(
                "selectRiskLevelCounts",
                "review_task",
                "",
                List.of("idx_review_task_dashboard_created_risk_norm"),
                List.of("range"),
                "review_task rows should stay bounded by created_at before grouping by risk_bucket_norm.",
                List.of("review_task key should prefer idx_review_task_dashboard_created_risk_norm")
            ),
            new ExplainTableExpectation(
                "selectReviewTrendCounts",
                "review_task",
                "",
                List.of("idx_review_task_dashboard_created_day", "idx_review_task_created_at"),
                List.of("range"),
                "review_task rows should stay bounded by created_at before created_date grouping.",
                List.of("review_task type should not be ALL")
            ),
            new ExplainTableExpectation(
                "selectRuleHitCounts",
                "review_task",
                "t",
                List.of("idx_review_task_created_at"),
                List.of("range", "ref", "eq_ref"),
                "review_task rows should be filtered by created_at before joining findings.",
                List.of("review_task key should include created_at")
            ),
            new ExplainTableExpectation(
                "selectRuleHitCounts",
                "review_finding",
                "f",
                List.of("idx_review_finding_task_category_rule"),
                List.of("ref", "eq_ref"),
                "review_finding rows should be reached by task_id/category rather than scanned.",
                List.of("review_finding type should not be ALL", "review_finding key should include task_id/category")
            ),
            new ExplainTableExpectation(
                "selectRecentHighRiskReviews",
                "review_task",
                "t",
                List.of("idx_review_task_dashboard_created_risk_norm"),
                List.of("range", "ref"),
                "review_task rows should be bounded by generated risk columns and created_at before applying limit.",
                List.of("review_task key should include risk_level_norm or created_at")
            ),
            new ExplainTableExpectation(
                "selectRecentHighRiskReviews",
                "review_finding",
                "f",
                List.of("idx_review_finding_task_category_rule"),
                List.of("ref", "eq_ref"),
                "review_finding rows should be joined by task_id/category for high-risk rule counts.",
                List.of("review_finding type should not be ALL")
            ),
            new ExplainTableExpectation(
                "selectLlmQualityTrendCounts",
                "review_task",
                "",
                List.of("idx_review_task_dashboard_created_llm_model_norm"),
                List.of("range"),
                "review_task rows should be bounded by created_at before LLM status filtering.",
                List.of("review_task key should prefer the LLM model dashboard index")
            ),
            new ExplainTableExpectation(
                "selectLlmQualityByModelStats",
                "review_task",
                "",
                List.of("idx_review_task_dashboard_created_llm_model_norm"),
                List.of("range"),
                "task_stats review_task rows should be window-bounded before model grouping.",
                List.of("task_stats derived table should not start from a full review_task scan")
            ),
            new ExplainTableExpectation(
                "selectLlmQualityByModelStats",
                "review_task",
                "t",
                List.of("idx_review_task_dashboard_created_llm_model_norm"),
                List.of("range", "ref", "eq_ref"),
                "feedback_stats review_task rows should be window-bounded before finding joins.",
                List.of("feedback_stats review_task key should include created_at")
            ),
            new ExplainTableExpectation(
                "selectLlmQualityByModelStats",
                "review_finding",
                "f",
                List.of("idx_review_finding_task_category_feedback_norm"),
                List.of("ref", "eq_ref"),
                "feedback_stats review_finding rows should be reached by task_id/category.",
                List.of("review_finding type should not be ALL")
            ),
            new ExplainTableExpectation(
                "selectLlmQualityByRepositoryStats",
                "review_task",
                "",
                List.of("idx_review_task_dashboard_created_llm_repo_norm"),
                List.of("range"),
                "task_stats review_task rows should be window-bounded before repository grouping.",
                List.of("task_stats derived table should not start from a full review_task scan")
            ),
            new ExplainTableExpectation(
                "selectLlmQualityByRepositoryStats",
                "review_task",
                "t",
                List.of("idx_review_task_dashboard_created_llm_repo_norm"),
                List.of("range", "ref", "eq_ref"),
                "feedback_stats review_task rows should be window-bounded before finding joins.",
                List.of("feedback_stats review_task key should include created_at")
            ),
            new ExplainTableExpectation(
                "selectLlmQualityByRepositoryStats",
                "review_finding",
                "f",
                List.of("idx_review_finding_task_category_feedback_norm"),
                List.of("ref", "eq_ref"),
                "feedback_stats review_finding rows should be reached by task_id/category.",
                List.of("review_finding type should not be ALL")
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

    public record ExplainTableExpectation(
        String mapperMethod,
        String tableName,
        String tableAlias,
        List<String> keyCandidates,
        List<String> acceptableAccessTypes,
        String rowsExpectation,
        List<String> extraWatchItems
    ) {
    }
}
