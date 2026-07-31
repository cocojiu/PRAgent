package com.repoguard.agent.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Locale;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class ReviewCalibrationQueueMapperSqlContractTest {

    @Test
    void queuePinsQualityVersionsAndOnlySelectsCompleteHighRiskAssessments() throws Exception {
        String sql = sampleSql();

        assertThat(sql)
            .contains("finding.category = 'finding'")
            .contains("finding.severity_norm in ('high', 'critical')")
            .contains("task.assessment_status")
            .contains("= 'complete'")
            .contains("finding.rule_config_version = #{ruleconfigversion}")
            .contains("finding.aggregation_version = #{aggregationversion}")
            .contains("finding.prompt_version = #{promptversion}")
            .contains("finding.context_version = #{contextversion}")
            .contains("finding.schema_version = #{schemaversion}")
            .contains("finding.verifier_version = #{verifierversion}")
            .contains("finding.detector_version")
            .contains("finding.feedback_status_norm = 'unreviewed'")
            .contains("finding.feedback_status_norm = 'ignored'");
    }

    @Test
    void queueCollapsesExactDuplicatesAndBalancesRepositories() throws Exception {
        String sql = sampleSql();

        assertThat(sql)
            .contains("row_number() over")
            .contains("partition by finding.task_id")
            .contains("as exactrank")
            .contains("where calibration_candidates.exactrank = 1")
            .contains("partition by calibration_candidates.repository")
            .contains("as repositoryrank")
            .contains("order by repositoryrank, taskcreatedat desc, findingid desc")
            .contains("limit #{limit}");
    }

    @Test
    void versionSummaryUsesTheSameWindowAndOnlyExplicitFeedbackAsLabels() throws Exception {
        Method method = ReviewCalibrationQueueMapper.class.getMethod(
            "selectVersionSummary",
            String.class,
            String.class,
            long.class,
            String.class,
            String.class,
            String.class,
            String.class,
            String.class
        );
        String sql = sql(method);

        assertThat(sql)
            .contains("task.assessment_status")
            .contains("finding.rule_config_version = #{ruleconfigversion}")
            .contains("finding.aggregation_version = #{aggregationversion}")
            .contains("feedback_status_norm in ('valid', 'fixed', 'false_positive')")
            .contains("feedback_status_norm in ('valid', 'fixed')")
            .contains("feedback_status_norm = 'false_positive'")
            .contains("feedback_status_norm not in ('valid', 'fixed', 'false_positive')")
            .contains("anchor_type <> 'none'")
            .contains("exactrank > 1");
    }

    private String sampleSql() throws NoSuchMethodException {
        Method method = ReviewCalibrationQueueMapper.class.getMethod(
            "selectPendingSamples",
            String.class,
            String.class,
            long.class,
            String.class,
            String.class,
            String.class,
            String.class,
            String.class,
            boolean.class,
            int.class
        );
        return sql(method);
    }

    private String sql(Method method) {
        Select select = method.getAnnotation(Select.class);
        assertThat(select).isNotNull();
        return String.join("\n", select.value()).replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
