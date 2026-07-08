package com.repoguard.agent.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Locale;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class ReviewFindingMapperSqlContractTest {

    @Test
    void reviewRuleFeedbackStatNormalizesFeedbackStatusBeforeAggregation() throws Exception {
        String sql = sql("selectReviewRuleFeedbackStat");

        assertThat(sql)
            .contains("from review_finding")
            .contains("where category = 'finding'");
        assertFeedbackStatusNormalization(sql);
    }

    @Test
    void githubCommentPreviewQueriesKeepCommentableStatusBoundary() throws Exception {
        String statSql = sql("selectGithubCommentPreviewFindingStat", Long.class);
        String commentableSql = sql("selectGithubCommentPreviewCommentableFindings", Long.class, long.class, int.class);

        assertThat(statSql)
            .contains("from review_finding finding")
            .contains("finding.category = 'finding'")
            .contains("publication.task_id = finding.task_id")
            .contains("publication.finding_id = finding.id")
            .contains("publication.published_success = 1")
            .contains("upper(coalesce(nullif(trim(finding.feedback_status), ''), 'unreviewed')) in ('unreviewed', 'valid')")
            .doesNotContain("publication.success = 1")
            .doesNotContain("trim(publication.github_url)")
            .doesNotContain("upper(finding.feedback_status) in ('unreviewed', 'valid')");
        assertThat(commentableSql)
            .contains("from review_finding finding")
            .contains("finding.category = 'finding'")
            .contains("publication.task_id = finding.task_id")
            .contains("publication.finding_id = finding.id")
            .contains("publication.published_success = 1")
            .contains("upper(coalesce(nullif(trim(finding.feedback_status), ''), 'unreviewed')) in ('unreviewed', 'valid')")
            .doesNotContain("publication.success = 1")
            .doesNotContain("trim(publication.github_url)")
            .doesNotContain("upper(finding.feedback_status) in ('unreviewed', 'valid')");
    }

    @Test
    void githubCommentPublishCandidateQueryUsesPublishedSuccessLookup() throws Exception {
        String sql = sql("selectGithubCommentPublishCandidatesAfterId", Long.class, long.class, int.class);

        assertThat(sql)
            .contains("from review_finding finding")
            .contains("finding.task_id = #{taskid}")
            .contains("finding.category = 'finding'")
            .contains("finding.id > #{afterfindingid}")
            .contains("not exists ( select 1 from github_comment_publication publication")
            .contains("publication.task_id = finding.task_id")
            .contains("publication.finding_id = finding.id")
            .contains("publication.published_success = 1")
            .contains("order by finding.id asc")
            .doesNotContain("publication.success = 1")
            .doesNotContain("trim(publication.github_url)");
    }

    @Test
    void findingSeverityCountsNormalizeSeverityBeforeAggregation() throws Exception {
        String sql = sql("selectFindingSeverityCounts", Long.class);

        assertThat(sql)
            .contains("from review_finding")
            .contains("where task_id = #{taskid}")
            .contains("category = 'finding'");
        assertSeverityNormalization(sql);
    }

    private String sql(String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
        Method method = ReviewFindingMapper.class.getMethod(methodName, parameterTypes);
        Select select = method.getAnnotation(Select.class);
        assertThat(select).as(methodName + " @Select").isNotNull();
        return normalizeSql(String.join("\n", select.value()));
    }

    private String normalizeSql(String sql) {
        return sql.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private void assertFeedbackStatusNormalization(String sql) {
        assertThat(sql)
            .contains("upper(coalesce(nullif(trim(feedback_status), ''), 'unreviewed')) = 'valid'")
            .contains("upper(coalesce(nullif(trim(feedback_status), ''), 'unreviewed')) = 'false_positive'")
            .contains("upper(coalesce(nullif(trim(feedback_status), ''), 'unreviewed')) <> 'unreviewed'");
    }

    private void assertSeverityNormalization(String sql) {
        assertThat(sql)
            .contains("lower(coalesce(nullif(trim(severity), ''), 'info')) = 'critical'")
            .contains("lower(coalesce(nullif(trim(severity), ''), 'info')) = 'high'")
            .contains("lower(coalesce(nullif(trim(severity), ''), 'info')) = 'medium'")
            .contains("lower(coalesce(nullif(trim(severity), ''), 'info')) = 'low'")
            .contains("lower(coalesce(nullif(trim(severity), ''), 'info')) not in ('critical', 'high', 'medium', 'low')")
            .doesNotContain("lower(severity)");
    }
}
