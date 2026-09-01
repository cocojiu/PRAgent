package com.repoguard.agent.review.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.dto.LlmModelReleaseDto;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.KeyHolder;

class LlmModelReleaseRepositoryTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final LlmModelReleaseRepository repository = new LlmModelReleaseRepository(jdbcTemplate);

    @Test
    void findMethodsMapRowsAndKeepTenantAndStateFilters() throws Exception {
        ResultSet resultSet = row("CANARY");
        when(jdbcTemplate.query(
            contains("order by updated_at"),
            org.mockito.ArgumentMatchers.<RowMapper<LlmModelReleaseDto>>any(),
            eq(new Object[] { 42L })
        )).thenAnswer(invocation -> List.of(invocation.<RowMapper<LlmModelReleaseDto>>getArgument(1).mapRow(resultSet, 0)));
        when(jdbcTemplate.query(
            contains("and state = ?"),
            org.mockito.ArgumentMatchers.<RowMapper<LlmModelReleaseDto>>any(),
            eq(new Object[] { 42L, "CANARY" })
        )).thenAnswer(invocation -> List.of(invocation.<RowMapper<LlmModelReleaseDto>>getArgument(1).mapRow(resultSet, 0)));
        when(jdbcTemplate.query(
            contains("and id = ?"),
            org.mockito.ArgumentMatchers.<RowMapper<LlmModelReleaseDto>>any(),
            eq(new Object[] { 42L, 7L })
        )).thenAnswer(invocation -> List.of(invocation.<RowMapper<LlmModelReleaseDto>>getArgument(1).mapRow(resultSet, 0)));

        assertThat(repository.findAll(42L)).singleElement().extracting(LlmModelReleaseDto::state).isEqualTo("CANARY");
        assertThat(repository.findByState(42L, "CANARY")).singleElement().extracting(LlmModelReleaseDto::id).isEqualTo(7L);
        assertThat(repository.findById(42L, 7L).datasetFingerprint()).hasSize(64);

        verify(jdbcTemplate).query(contains("and state = ?"),
            org.mockito.ArgumentMatchers.<RowMapper<LlmModelReleaseDto>>any(), eq(new Object[] { 42L, "CANARY" }));
    }

    @Test
    void findByIdRejectsMissingRecord() {
        when(jdbcTemplate.query(contains("and id = ?"),
            org.mockito.ArgumentMatchers.<RowMapper<LlmModelReleaseDto>>any(), eq(new Object[] { 42L, 99L }))).thenReturn(List.of());

        assertThatThrownBy(() -> repository.findById(42L, 99L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不存在");
    }

    @Test
    void saveUpdatesExistingReleaseAndReturnsFreshRow() throws Exception {
        LlmModelReleaseService.NormalizedRelease draft = draft();
        LlmModelReleaseDto stored = rowDto("SHADOW");
        when(jdbcTemplate.query(contains("release_key = ?"),
            org.mockito.ArgumentMatchers.<RowMapper<Long>>any(), eq(new Object[] { 42L, "release-1" })))
            .thenReturn(List.of(55L));
        when(jdbcTemplate.query(contains("and id = ?"),
            org.mockito.ArgumentMatchers.<RowMapper<LlmModelReleaseDto>>any(), eq(new Object[] { 42L, 55L })))
            .thenReturn(List.of(stored));

        assertThat(repository.save(42L, draft, "CANARY", 25, "operator", "")).isEqualTo(stored);

        verify(jdbcTemplate).update(contains("update llm_model_release"),
            eq("openai"), eq("gpt-next"), eq("prompt-v1"), eq("context-v1"), eq("schema-v1"),
            eq("dataset-1"), eq("v1"), eq(draft.datasetFingerprint()), eq("CANARY"), eq(25), eq(true),
            eq(draft.precisionRate()), eq(draft.recallRate()), eq(draft.anchorRate()), eq(draft.duplicateRate()),
            eq(draft.parseFailureRate()), eq(1000L), eq(draft.averageCost()), eq(1000L), eq(""), eq(null),
            eq("operator"), eq(42L), eq("release-1"));
    }

    @Test
    void saveInsertsNewReleaseUsingGeneratedKey() throws Exception {
        LlmModelReleaseService.NormalizedRelease draft = draft();
        LlmModelReleaseDto stored = rowDto("SHADOW");
        when(jdbcTemplate.query(contains("release_key = ?"),
            org.mockito.ArgumentMatchers.<RowMapper<Long>>any(), eq(new Object[] { 42L, "release-1" })))
            .thenReturn(List.of());
        when(jdbcTemplate.update(any(PreparedStatementCreator.class), any(KeyHolder.class))).thenAnswer(invocation -> {
            KeyHolder keyHolder = invocation.getArgument(1);
            keyHolder.getKeyList().add(Map.of("GENERATED_KEY", 56L));
            return 1;
        });
        when(jdbcTemplate.query(contains("and id = ?"),
            org.mockito.ArgumentMatchers.<RowMapper<LlmModelReleaseDto>>any(), eq(new Object[] { 42L, 56L })))
            .thenReturn(List.of(stored));

        assertThat(repository.save(42L, draft, "SHADOW", 0, "operator", "")).isEqualTo(stored);
        verify(jdbcTemplate).update(any(PreparedStatementCreator.class), any(KeyHolder.class));
    }

    @Test
    void lifecycleUpdatesReturnAffectedRows() {
        when(jdbcTemplate.update(contains("state = 'ACTIVE'"), eq(42L))).thenReturn(1);
        when(jdbcTemplate.update(contains("state <> 'ROLLED_BACK'"), eq("reason"), eq(42L), eq(7L))).thenReturn(1);

        assertThat(repository.markActiveReplaced(42L)).isEqualTo(1);
        assertThat(repository.rollback(42L, 7L, "reason")).isEqualTo(1);
    }

    private ResultSet row(String state) throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getLong("id")).thenReturn(7L);
        when(resultSet.getString("release_key")).thenReturn("release-7");
        when(resultSet.getString("provider")).thenReturn("openai");
        when(resultSet.getString("model_name")).thenReturn("gpt-next");
        when(resultSet.getString("prompt_version")).thenReturn("prompt-v1");
        when(resultSet.getString("context_version")).thenReturn("context-v1");
        when(resultSet.getString("schema_version")).thenReturn("schema-v1");
        when(resultSet.getString("dataset_id")).thenReturn("dataset-1");
        when(resultSet.getString("dataset_version")).thenReturn("v1");
        when(resultSet.getString("dataset_fingerprint")).thenReturn(FINGERPRINT);
        when(resultSet.getString("state")).thenReturn(state);
        when(resultSet.getInt("traffic_percent")).thenReturn(25);
        when(resultSet.getBoolean("quality_gate_passed")).thenReturn(true);
        when(resultSet.getBigDecimal("precision_rate")).thenReturn(new BigDecimal("0.95"));
        when(resultSet.getBigDecimal("recall_rate")).thenReturn(new BigDecimal("0.85"));
        when(resultSet.getBigDecimal("anchor_rate")).thenReturn(new BigDecimal("0.98"));
        when(resultSet.getBigDecimal("duplicate_rate")).thenReturn(new BigDecimal("0.01"));
        when(resultSet.getBigDecimal("parse_failure_rate")).thenReturn(new BigDecimal("0.01"));
        when(resultSet.getLong("p95_latency_ms")).thenReturn(1000L);
        when(resultSet.getBigDecimal("average_cost")).thenReturn(new BigDecimal("0.01"));
        when(resultSet.getLong("total_tokens")).thenReturn(1000L);
        when(resultSet.getString("blockers")).thenReturn("one,,two");
        when(resultSet.getString("rollback_reason")).thenReturn(null);
        when(resultSet.getString("created_by")).thenReturn("tester");
        when(resultSet.getTimestamp("created_at")).thenReturn(Timestamp.valueOf("2026-09-01 00:00:00"));
        when(resultSet.getTimestamp("updated_at")).thenReturn(Timestamp.valueOf("2026-09-01 00:00:00"));
        return resultSet;
    }

    private LlmModelReleaseDto rowDto(String state) {
        return new LlmModelReleaseDto(55L, "release-1", "openai", "gpt-next", "prompt-v1", "context-v1", "schema-v1",
            "dataset-1", "v1", FINGERPRINT, state, 0, true, new BigDecimal("0.95"), new BigDecimal("0.85"),
            new BigDecimal("0.98"), new BigDecimal("0.01"), new BigDecimal("0.01"), 1000L, new BigDecimal("0.01"),
            1000L, List.of(), null, "tester", null, null);
    }

    private LlmModelReleaseService.NormalizedRelease draft() {
        return new LlmModelReleaseService.NormalizedRelease(
            "release-1", "openai", "gpt-next", "prompt-v1", "context-v1", "schema-v1", "dataset-1", "v1",
            FINGERPRINT, 25, true, new BigDecimal("0.95"), new BigDecimal("0.85"), new BigDecimal("0.98"),
            new BigDecimal("0.01"), new BigDecimal("0.01"), 1000L, new BigDecimal("0.01"), 1000L, List.of(), "operator"
        );
    }

    private static final String FINGERPRINT = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
}
