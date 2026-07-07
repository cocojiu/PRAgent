package com.repoguard.agent.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Locale;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class ReviewTaskMapperSqlContractTest {

    @Test
    void messageQueueHealthSummaryNormalizesStatusBeforeAggregation() throws Exception {
        String sql = sql("selectMessageQueueHealthSummary");

        assertThat(sql)
            .contains("from review_task")
            .contains("count(*) as total")
            .contains("publish_claimed_at is not null");
        assertMqStatusNormalization(sql);
    }

    @Test
    void messageQueueExceptionTaskQueryNormalizesStatusBeforeFiltering() throws Exception {
        String sql = sql("selectMessageQueueExceptionTasks");

        assertThat(sql)
            .contains("select *")
            .contains("from review_task")
            .contains("order by created_at desc")
            .contains("limit 20")
            .contains("upper(coalesce(nullif(trim(status), ''), 'unknown')) in ('publish_failed', 'execution_timeout', 'requeue_pending', 'dlq')");
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

    private void assertMqStatusNormalization(String sql) {
        assertThat(sql)
            .contains("upper(coalesce(nullif(trim(status), ''), 'unknown')) = 'publish_failed'")
            .contains("upper(coalesce(nullif(trim(status), ''), 'unknown')) = 'execution_timeout'")
            .contains("upper(coalesce(nullif(trim(status), ''), 'unknown')) = 'requeue_pending'")
            .contains("upper(coalesce(nullif(trim(status), ''), 'unknown')) = 'dlq'")
            .doesNotContain("status = 'publish_failed'")
            .doesNotContain("status in ('publish_failed'");
    }
}
