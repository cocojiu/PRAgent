package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.repoguard.agent.dto.ReviewQuery;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.review.ReviewTaskCursorCodec;
import com.repoguard.agent.security.AuthProperties;
import java.time.LocalDateTime;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ReviewTaskListQueryBuilderTest {

    private final ReviewTaskCursorCodec cursorCodec = cursorCodec();
    private final ReviewTaskListQueryBuilder builder = new ReviewTaskListQueryBuilder(cursorCodec);

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

        assertThat(criteria.organization()).isNull();
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
    void splitsRepositoryDimensionLabelIntoOrganizationAndRepositoryFilters() {
        var query = new ReviewQuery(
            1,
            20,
            " codex/repo-guard ",
            null,
            null,
            null,
            null,
            null
        );

        var criteria = builder.normalize(query);
        var wrapper = builder.build(query);

        assertThat(criteria.organization()).isEqualTo("codex");
        assertThat(criteria.repository()).isEqualTo("repo-guard");
        assertThat(wrapper.getSqlSegment()).contains("organization", "repository");
        assertThat(wrapper.getParamNameValuePairs().values()).contains("codex", "repo-guard");
    }

    @Test
    void keepsLegacyRepositoryOnlyFilterWhenLabelHasNoOrganization() {
        var criteria = builder.normalize(new ReviewQuery(
            1,
            20,
            "repo-guard",
            null,
            null,
            null,
            null,
            null
        ));

        assertThat(criteria.organization()).isNull();
        assertThat(criteria.repository()).isEqualTo("repo-guard");
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
        var baseQuery = new ReviewQuery(
            3,
            500,
            "repo-a",
            "completed",
            null,
            null,
            null,
            null,
            null
        );
        var query = new ReviewQuery(
            3,
            500,
            "repo-a",
            "completed",
            null,
            null,
            null,
            null,
            builder.encodeCursor(baseQuery, LocalDateTime.of(2026, 7, 8, 12, 0), 123L, 42L)
        );

        var wrapper = builder.buildKeysetPage(query);

        assertThat(builder.hasKeysetCursor(query)).isTrue();
        assertThat(wrapper.getSqlSegment())
            .contains("created_at", "<", "id", "ORDER BY")
            .contains("created_at DESC", "id DESC");
        assertThat(wrapper.getTargetSql()).contains("limit 101");
        assertThat(wrapper.getParamNameValuePairs().values())
            .anySatisfy(value -> assertThat(value).hasToString("2026-07-08T12:00"))
            .contains(123L);
    }

    @Test
    void rejectsInvalidCursorInsteadOfFallingBackToOffsetPagination() {
        var query = new ReviewQuery(
            1,
            20,
            "repo-a",
            null,
            null,
            null,
            null,
            null,
            "not-a-cursor"
        );

        assertThatThrownBy(() -> builder.buildCountQuery(query))
            .isInstanceOf(com.repoguard.agent.common.BusinessException.class)
            .hasMessage("Invalid review list cursor");
    }

    @Test
    void rejectsCursorWhenQueryScopeChanges() {
        ReviewQuery sourceQuery = new ReviewQuery(1, 20, "repo-a", null, null, null, null, null);
        String cursor = builder.encodeCursor(sourceQuery, LocalDateTime.of(2026, 7, 8, 12, 0), 123L, 42L);

        assertThatThrownBy(() -> builder.hasKeysetCursor(new ReviewQuery(
            1,
            20,
            "repo-b",
            null,
            null,
            null,
            null,
            null,
            cursor
        )))
            .isInstanceOf(com.repoguard.agent.common.BusinessException.class)
            .hasMessage("Invalid review list cursor");
    }

    @Test
    void rejectsCursorWhenSignedPayloadIsTampered() {
        ReviewQuery query = queryWithoutCursor(new ReviewQuery(
            1,
            20,
            null,
            null,
            null,
            null,
            null,
            null
        ));
        String cursor = builder.encodeCursor(query, LocalDateTime.of(2026, 7, 8, 12, 0), 123L, 42L);
        String tamperedCursor = (cursor.charAt(0) == 'A' ? "B" : "A") + cursor.substring(1);

        assertThatThrownBy(() -> builder.hasKeysetCursor(new ReviewQuery(
            query.page(),
            query.pageSize(),
            query.repository(),
            query.status(),
            query.riskLevel(),
            query.source(),
            query.triggerSource(),
            query.keyword(),
            tamperedCursor
        )))
            .isInstanceOf(com.repoguard.agent.common.BusinessException.class)
            .hasMessage("Invalid review list cursor");
    }

    private ReviewQuery queryWithoutCursor(ReviewQuery query) {
        return new ReviewQuery(
            query.page(),
            query.pageSize(),
            query.repository(),
            query.status(),
            query.riskLevel(),
            query.source(),
            query.triggerSource(),
            query.keyword()
        );
    }

    private ReviewTaskCursorCodec cursorCodec() {
        AuthProperties properties = new AuthProperties();
        properties.setTokenSecret("review-task-cursor-test-secret-32-characters");
        properties.setTokenSecretId("review-test");
        return new ReviewTaskCursorCodec(properties);
    }
}
