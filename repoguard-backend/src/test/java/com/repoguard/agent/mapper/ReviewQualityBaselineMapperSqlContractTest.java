package com.repoguard.agent.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Locale;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class ReviewQualityBaselineMapperSqlContractTest {

    @Test
    void summaryKeepsExplicitFeedbackAndQualityMetricSemantics() throws Exception {
        String sql = sql("selectSummary");

        assertThat(sql)
            .contains("from review_finding finding")
            .contains("join review_task task on task.id = finding.task_id")
            .contains("finding.category = 'finding'")
            .contains("task.assessment_status = 'complete'")
            .contains("finding.severity_norm in ('high', 'critical')")
            .contains("finding.feedback_status_norm in ('valid', 'fixed', 'false_positive')")
            .contains("finding.feedback_status_norm in ('valid', 'fixed')")
            .contains("finding.feedback_status_norm = 'false_positive'")
            .contains("finding.anchor_type <> 'none'")
            .contains("sum(duplicates.exactcount - 1)")
            .contains("group by duplicatefinding.task_id")
            .contains("join review_task duplicatetask on duplicatetask.id = duplicatefinding.task_id")
            .contains("duplicatetask.assessment_status = 'complete'")
            .doesNotContain("feedback_status_norm <> 'unreviewed'");
    }

    @Test
    void groupsCoverFeedbackDimensionsVersionTraceAndBlockRevocation() throws Exception {
        String sql = sql("selectGroups");

        assertThat(sql)
            .contains("join review_task task on task.id = finding.task_id")
            .contains("task.assessment_status = 'complete'")
            .contains("as ruleid")
            .contains("as source")
            .contains("as repository")
            .contains("as language")
            .contains("as severity")
            .contains("as versionkey")
            .contains("finding.detector_version as detectorversion")
            .contains("finding.rule_config_version as ruleconfigversion")
            .contains("finding.policy_version as policyversion")
            .contains("finding.prompt_version as promptversion")
            .contains("finding.context_version as contextversion")
            .contains("finding.schema_version as schemaversion")
            .contains("finding.verifier_version as verifierversion")
            .contains("finding.aggregation_version as aggregationversion")
            .contains("feedback_status_norm in ('valid', 'fixed')")
            .contains("feedback_status_norm = 'false_positive'")
            .contains("feedback_status_norm not in ('valid', 'fixed', 'false_positive')")
            .contains("finding.original_is_blocking = 1")
            .contains("finding.exact_rank > 1")
            .contains("group by ruleid, source, repository, language, severity")
            .contains("like '%.java'")
            .contains("like '%.sql'")
            .contains("like '%.yml'");
    }

    @Test
    void executionBaselineUsesFinishedTasksForLatencyAndCost() throws Exception {
        String sql = sql("selectExecution");

        assertThat(sql)
            .contains("from review_task task")
            .contains("where task.finished_at is not null")
            .contains("avg(task.duration_seconds)")
            .contains("sum(task.llm_estimated_cost)");
    }

    private String sql(String methodName) throws NoSuchMethodException {
        Method method = ReviewQualityBaselineMapper.class.getMethod(methodName);
        Select select = method.getAnnotation(Select.class);
        assertThat(select).as(methodName + " @Select").isNotNull();
        return String.join("\n", select.value()).replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
