package com.repoguard.agent.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Locale;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

class ReviewQualityBaselineSnapshotMapperSqlContractTest {

    @Test
    void dirtyMarkerMonotonicallyAdvancesTheSourceVersion() throws Exception {
        String sql = sql("markDirty", Insert.class);

        assertThat(sql)
            .contains("insert into review_quality_baseline_snapshot")
            .contains("values ('global', 1, 0, null, null)")
            .contains("on duplicate key update")
            .contains("source_version = source_version + 1");
    }

    @Test
    void refreshWritesPayloadOnlyForANonNewerRefreshedVersion() throws Exception {
        String sql = sql("markRefreshed", Update.class, long.class, String.class, java.time.LocalDateTime.class);

        assertThat(sql)
            .contains("baseline_payload = cast(#{baselinepayload} as json)")
            .contains("refreshed_version = #{sourceversion}")
            .contains("where snapshot_key = 'global'")
            .contains("refreshed_version <= #{sourceversion}");
    }

    @Test
    void stateReadExposesSourceAndRefreshedVersions() throws Exception {
        String sql = sql("selectState", Select.class);

        assertThat(sql)
            .contains("source_version as sourceversion")
            .contains("refreshed_version as refreshedversion")
            .contains("baseline_payload as baselinepayload")
            .contains("where snapshot_key = 'global'");
    }

    private <A extends java.lang.annotation.Annotation> String sql(
        String methodName,
        Class<A> annotationType,
        Class<?>... parameterTypes
    ) throws Exception {
        Method method = ReviewQualityBaselineSnapshotMapper.class.getMethod(methodName, parameterTypes);
        A annotation = method.getAnnotation(annotationType);
        assertThat(annotation).as(methodName + " @" + annotationType.getSimpleName()).isNotNull();
        String[] value = switch (annotation) {
            case Select select -> select.value();
            case Insert insert -> insert.value();
            case Update update -> update.value();
            default -> throw new IllegalArgumentException("Unsupported SQL annotation: " + annotationType);
        };
        return String.join("\n", value).replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
