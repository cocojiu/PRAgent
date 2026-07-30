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
            .contains("finding.category = 'finding'")
            .contains("finding.severity_norm in ('high', 'critical')")
            .contains("finding.feedback_status_norm in ('valid', 'fixed', 'false_positive')")
            .contains("finding.feedback_status_norm in ('valid', 'fixed')")
            .contains("finding.feedback_status_norm = 'false_positive'")
            .contains("finding.line_number is not null and finding.line_number > 0")
            .contains("sum(duplicates.exactcount - 1)")
            .contains("group by duplicatefinding.task_id")
            .doesNotContain("feedback_status_norm <> 'unreviewed'");
    }

    @Test
    void groupsCoverRuleSourceRepositoryLanguageAndSeverityDimensions() throws Exception {
        String sql = sql("selectGroups");

        assertThat(sql)
            .contains("join review_task task on task.id = finding.task_id")
            .contains("as ruleid")
            .contains("as source")
            .contains("as repository")
            .contains("as language")
            .contains("as severity")
            .contains("feedback_status_norm in ('valid', 'fixed')")
            .contains("feedback_status_norm = 'false_positive'")
            .contains("feedback_status_norm not in ('valid', 'fixed', 'false_positive')")
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
