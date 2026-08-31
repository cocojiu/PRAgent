package com.repoguard.agent.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.lang.reflect.Method;
import java.util.Locale;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class ChangedFileMapperSqlContractTest {

    @Test
    void changedFileFindingPresenceQueriesUseExistsInsteadOfDistinctInSubqueries() throws Exception {
        String withFindings = sql("selectChangedFilesWithFindings", Page.class, Long.class);
        String withoutFindings = sql("selectChangedFilesWithoutFindings", Page.class, Long.class);

        assertFindingPresenceSql(withFindings)
            .contains("and exists ( select 1 from review_finding finding");
        assertFindingPresenceSql(withoutFindings)
            .contains("and not exists ( select 1 from review_finding finding");
    }

    private org.assertj.core.api.AbstractStringAssert<?> assertFindingPresenceSql(String sql) {
        return assertThat(sql)
            .contains("from changed_file file")
            .contains("where file.task_id = #{taskid}")
            .contains("file.current_attempt = 1")
            .contains("from review_finding finding")
            .contains("finding.task_id = file.task_id")
            .contains("finding.current_attempt = 1")
            .contains("finding.category = 'finding'")
            .contains("finding.file_path = file.file_path")
            .contains("order by file.id asc")
            .doesNotContain("select distinct")
            .doesNotContain("force index")
            .doesNotContain(" in (")
            .doesNotContain(" not in (");
    }

    private String sql(String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
        Method method = ChangedFileMapper.class.getMethod(methodName, parameterTypes);
        Select select = method.getAnnotation(Select.class);
        assertThat(select).as(methodName + " @Select").isNotNull();
        return normalizeSql(String.join("\n", select.value()));
    }

    private String normalizeSql(String sql) {
        return sql.replace("<script>", "")
            .replace("</script>", "")
            .replaceAll("\\s+", " ")
            .trim()
            .toLowerCase(Locale.ROOT);
    }
}
