package com.repoguard.agent.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class ReviewTaskArchiveSummaryMapperSqlContractTest {

    @Test
    void archiveSummaryInsertSelectKeepsHistoricalLookupFieldsBeforeCleanup() throws Exception {
        String sql = insertSql("insertArchiveSummaries", long.class, String.class, List.class);

        assertThat(sql)
            .contains("insert into review_task_archive_summary")
            .contains("task_id, cleanup_batch_id")
            .contains("#{cleanupbatchid} as cleanup_batch_id")
            .contains("#{backupreference} as backup_reference")
            .contains("task.assessment_status")
            .contains("assessment_status = values(assessment_status)")
            .contains("from review_task task")
            .contains("left join")
            .contains("where category = 'finding'")
            .contains("missing_test_count")
            .contains("where category = 'missing_test'")
            .contains("from review_finding")
            .contains("from changed_file")
            .contains("from review_timeline")
            .contains("from github_comment_publication")
            .contains("where task.id in")
            .contains("<foreach collection=\"taskids\"")
            .contains("on duplicate key update")
            .doesNotContain("delete from")
            .doesNotContain("truncate");
    }

    @Test
    void archivedDetailLookupUsesTaskIdIndex() throws Exception {
        String sql = selectSql("selectByTaskId", Long.class);

        assertThat(sql)
            .contains("from review_task_archive_summary")
            .contains("where task_id = #{taskid}")
            .doesNotContain("from review_task ");
    }

    private String insertSql(String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
        Method method = ReviewTaskArchiveSummaryMapper.class.getMethod(methodName, parameterTypes);
        Insert insert = method.getAnnotation(Insert.class);
        assertThat(insert).as(methodName + " @Insert").isNotNull();
        return normalizeSql(String.join("\n", insert.value()));
    }

    private String selectSql(String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
        Method method = ReviewTaskArchiveSummaryMapper.class.getMethod(methodName, parameterTypes);
        Select select = method.getAnnotation(Select.class);
        assertThat(select).as(methodName + " @Select").isNotNull();
        return normalizeSql(String.join("\n", select.value()));
    }

    private String normalizeSql(String sql) {
        return sql.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
