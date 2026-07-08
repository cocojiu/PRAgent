package com.repoguard.agent.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Locale;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class ReviewRepositoryDimensionMapperSqlContractTest {

    @Test
    void repositoryOptionsComeFromDimensionTable() throws Exception {
        String sql = selectSql("selectActiveRepositoryLabels");

        assertThat(sql)
            .contains("select repository_label")
            .contains("from review_repository_dimension")
            .contains("where active = 1")
            .contains("order by repository_label asc")
            .doesNotContain("select distinct repository")
            .doesNotContain("from review_task");
    }

    @Test
    void upsertMaintainsOrganizationRepositoryDimension() throws Exception {
        String sql = insertSql(
            "upsertRepository",
            String.class,
            String.class,
            LocalDateTime.class
        );

        assertThat(sql)
            .contains("insert into review_repository_dimension")
            .contains("concat(#{organization}, '/', #{repository})")
            .contains("on duplicate key update")
            .contains("task_count = task_count + 1")
            .contains("active = 1");
    }

    private String selectSql(String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
        Method method = ReviewRepositoryDimensionMapper.class.getMethod(methodName, parameterTypes);
        Select select = method.getAnnotation(Select.class);
        assertThat(select).as(methodName + " @Select").isNotNull();
        return normalizeSql(String.join("\n", select.value()));
    }

    private String insertSql(String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
        Method method = ReviewRepositoryDimensionMapper.class.getMethod(methodName, parameterTypes);
        Insert insert = method.getAnnotation(Insert.class);
        assertThat(insert).as(methodName + " @Insert").isNotNull();
        return normalizeSql(String.join("\n", insert.value()));
    }

    private String normalizeSql(String sql) {
        return sql.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
