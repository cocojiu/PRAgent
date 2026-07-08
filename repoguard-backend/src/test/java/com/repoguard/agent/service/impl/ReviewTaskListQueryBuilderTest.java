package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.repoguard.agent.dto.ReviewQuery;
import com.repoguard.agent.entity.ReviewTask;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ReviewTaskListQueryBuilderTest {

    private final ReviewTaskListQueryBuilder builder = new ReviewTaskListQueryBuilder();

    @BeforeAll
    static void initMybatisPlusTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ReviewTask.class);
    }

    @Test
    void buildsNormalizedFiltersAndNumericKeywordCondition() {
        var query = new ReviewQuery(
            1,
            20,
            " repo-a ",
            " failed ",
            " high ",
            " manual_input ",
            " github_webhook ",
            "42"
        );

        var criteria = builder.normalize(query);
        var wrapper = builder.build(query);

        assertThat(criteria.repository()).isEqualTo("repo-a");
        assertThat(criteria.status()).isEqualTo("FAILED");
        assertThat(criteria.riskLevel()).isEqualTo("HIGH");
        assertThat(criteria.source()).isEqualTo("MANUAL_INPUT");
        assertThat(criteria.triggerSource()).isEqualTo("GITHUB_WEBHOOK");
        assertThat(criteria.keyword()).isEqualTo("42");
        assertThat(criteria.prNumber()).isEqualTo(42);
        assertThat(criteria.commitPrefix()).isNull();
        assertThat(criteria.textKeyword()).isNull();
        assertThat(wrapper.getSqlSegment()).contains("ORDER BY");
        assertThat(wrapper.getSqlSegment()).contains("created_at", "id");
        assertThat(wrapper.getSqlSegment()).contains("pr_number");
    }

    @Test
    void normalizesTextKeywordWhenItIsSelectiveEnough() {
        var criteria = builder.normalize(new ReviewQuery(
            1,
            20,
            " ",
            null,
            "",
            null,
            " ",
            "security"
        ));

        assertThat(criteria.repository()).isNull();
        assertThat(criteria.status()).isNull();
        assertThat(criteria.riskLevel()).isNull();
        assertThat(criteria.source()).isNull();
        assertThat(criteria.triggerSource()).isNull();
        assertThat(criteria.keyword()).isEqualTo("security");
        assertThat(criteria.prNumber()).isNull();
        assertThat(criteria.commitPrefix()).isNull();
        assertThat(criteria.textKeyword()).isEqualTo("security");
    }

    @Test
    void textKeywordUsesPrefixMatchToKeepIndexesUsable() {
        var wrapper = builder.build(new ReviewQuery(1, 20, null, null, null, null, null, "security"));

        assertThat(wrapper.getSqlSegment()).contains("title", "repository", "organization");
        assertThat(wrapper.getParamNameValuePairs().values())
            .containsOnly("security%");
    }

    @Test
    void skipsShortTextKeywordToAvoidWideTableScan() {
        var criteria = builder.normalize(new ReviewQuery(1, 20, null, null, null, null, null, "ab"));
        var wrapper = builder.build(new ReviewQuery(1, 20, null, null, null, null, null, "ab"));

        assertThat(criteria.keyword()).isEqualTo("ab");
        assertThat(criteria.prNumber()).isNull();
        assertThat(criteria.commitPrefix()).isNull();
        assertThat(criteria.textKeyword()).isNull();
        assertThat(wrapper.getSqlSegment()).doesNotContain("LIKE");
    }

    @Test
    void treatsLongHexKeywordAsCommitPrefix() {
        var criteria = builder.normalize(new ReviewQuery(1, 20, null, null, null, null, null, "abcdef0"));
        var wrapper = builder.build(new ReviewQuery(1, 20, null, null, null, null, null, "abcdef0"));

        assertThat(criteria.prNumber()).isNull();
        assertThat(criteria.commitPrefix()).isEqualTo("abcdef0");
        assertThat(criteria.textKeyword()).isNull();
        assertThat(wrapper.getSqlSegment()).contains("commit_sha");
    }

    @Test
    void buildsKeysetCursorConditionWithStableOrderAndBoundedLimit() {
        var query = new ReviewQuery(
            3,
            500,
            "repo-a",
            "completed",
            null,
            null,
            null,
            null,
            "2026-07-08 12:00:00",
            123L
        );

        var wrapper = builder.buildKeysetPage(query);

        assertThat(builder.hasKeysetCursor(query)).isTrue();
        assertThat(wrapper.getSqlSegment())
            .contains("created_at", "<", "id", "ORDER BY")
            .contains("created_at DESC", "id DESC");
        assertThat(wrapper.getTargetSql()).contains("limit 100");
        assertThat(wrapper.getParamNameValuePairs().values())
            .anySatisfy(value -> assertThat(value).hasToString("2026-07-08T12:00"))
            .contains(123L);
    }

    @Test
    void ignoresInvalidCursorAndKeepsCountQueryUnordered() {
        var query = new ReviewQuery(
            1,
            20,
            "repo-a",
            null,
            null,
            null,
            null,
            null,
            "not-a-date",
            0L
        );

        var countWrapper = builder.buildCountQuery(query);

        assertThat(builder.hasKeysetCursor(query)).isFalse();
        assertThat(countWrapper.getSqlSegment()).doesNotContain("ORDER BY", "limit", "<");
    }
}
