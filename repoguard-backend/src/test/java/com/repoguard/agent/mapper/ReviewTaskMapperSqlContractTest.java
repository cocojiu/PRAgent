package com.repoguard.agent.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Locale;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class ReviewTaskMapperSqlContractTest {

    @Test
    void listSummaryStatAggregatesNormalizedColumnsBehindSharedFilterSegment() throws Exception {
        String sql = sql("selectListSummaryStat", Wrapper.class);

        assertThat(sql)
            .contains("from review_task")
            .contains("count(*) as total")
            .contains("sum(case when risk_level_norm in ('high', 'critical') then 1 else 0 end) as highrisk")
            .contains("sum(case when status_norm = 'failed' then 1 else 0 end) as failed")
            .contains("avg(case when finished_at is not null then duration_seconds end) as averagedurationseconds")
            .contains("${ew.customsqlsegment}")
            .doesNotContain("order by")
            .doesNotContain("where created_at");
    }

    @Test
    void messageQueueHealthSummaryUsesNormalizedStatusBeforeAggregation() throws Exception {
        String sql = sql("selectMessageQueueHealthSummary", LocalDateTime.class);

        assertThat(sql)
            .contains("from review_task")
            .contains("count(*) as total")
            .contains("publish_claimed_at is not null")
            .contains("where created_at >= #{createdafter}");
        assertMqStatusNormColumn(sql);
    }

    @Test
    void latestPublishFailureReasonQueryIsBoundedByCreatedAt() throws Exception {
        String sql = sql("selectLatestPublishFailureReason", LocalDateTime.class);

        assertThat(sql)
            .contains("from review_task")
            .contains("where created_at >= #{createdafter}")
            .contains("last_publish_error is not null")
            .contains("order by created_at desc")
            .contains("limit 1");
    }

    @Test
    void messageQueueExceptionTaskQueryUsesNormalizedStatusBeforeFiltering() throws Exception {
        String sql = sql("selectMessageQueueExceptionTasks");

        assertThat(sql)
            .contains("select *")
            .contains("from review_task")
            .contains("order by created_at desc")
            .contains("limit 20")
            .contains("status_norm in ('publish_failed', 'execution_timeout', 'requeue_pending', 'dlq')")
            .doesNotContain("upper(coalesce(nullif(trim(status)");
    }

    private String sql(String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
        Method method = ReviewTaskMapper.class.getMethod(methodName, parameterTypes);
        Select select = method.getAnnotation(Select.class);
        assertThat(select).as(methodName + " @Select").isNotNull();
        return normalizeSql(String.join("\n", select.value()));
    }

    private String normalizeSql(String sql) {
        return sql.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private void assertMqStatusNormColumn(String sql) {
        assertThat(sql)
            .contains("status_norm = 'publish_failed'")
            .contains("status_norm = 'execution_timeout'")
            .contains("status_norm = 'requeue_pending'")
            .contains("status_norm = 'dlq'")
            .doesNotContain("upper(coalesce(nullif(trim(status)")
            .doesNotContain("status = 'publish_failed'")
            .doesNotContain("status in ('publish_failed'");
    }
}
