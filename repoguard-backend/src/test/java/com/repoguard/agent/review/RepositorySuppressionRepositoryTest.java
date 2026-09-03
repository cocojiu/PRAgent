package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.KeyHolder;

@SuppressWarnings({"rawtypes", "unchecked"})
class RepositorySuppressionRepositoryTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final RepositorySuppressionRepository repository = new RepositorySuppressionRepository(jdbcTemplate);
    private final LocalDateTime now = LocalDateTime.of(2026, 1, 1, 0, 0);

    @Test
    void insertsReadsTransitionsAndTracksHitsWithTenantScopedSql() throws Exception {
        PreparedStatement statement = mock(PreparedStatement.class);
        Connection connection = mock(Connection.class);
        when(connection.prepareStatement(anyString(), eq(Statement.RETURN_GENERATED_KEYS))).thenReturn(statement);
        doAnswer(invocation -> {
            invocation.<org.springframework.jdbc.core.PreparedStatementCreator>getArgument(0)
                .createPreparedStatement(connection);
            KeyHolder holder = invocation.getArgument(1);
            holder.getKeyList().add(Map.of("GENERATED_KEY", 9L));
            return 1;
        }).when(jdbcTemplate).update(any(org.springframework.jdbc.core.PreparedStatementCreator.class), any(KeyHolder.class));

        RepositorySuppressionRepository.StoredSuppression stored = stored("PROPOSED");
        stubRows(stored);
        RepositorySuppressionRepository.StoredSuppression inserted = repository.insert(
            7L, "octocat", "repo", "RG-AUTH-001", "src/**", "Auth", "reason", "alice", now.plusDays(30), 4
        );

        assertThat(inserted).isNotNull();
        assertThat(inserted.id()).isEqualTo(9L);
        repository.incrementHit(7L, 9L);
        assertThat(repository.find(7L, 9L).status()).isEqualTo("PROPOSED");
        assertThat(repository.list(7L, "octocat", "repo", 50)).hasSize(1);
        assertThat(repository.activeFor(7L, "octocat", "repo")).hasSize(1);
    }

    @Test
    void handlesTransitionExpirationAndMissingRows() {
        RepositorySuppressionRepository.StoredSuppression proposed = stored("PROPOSED");
        RepositorySuppressionRepository.StoredSuppression active = stored("ACTIVE");
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any())).thenReturn(1);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
            .thenAnswer(invocation -> rowsFor(invocation, proposed));
        assertThat(repository.transition(7L, 9L, "PROPOSED", "ACTIVE", "alice", "approved").status())
            .isEqualTo("PROPOSED");
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
            .thenAnswer(invocation -> {
                RowMapper<?> mapper = invocation.getArgument(1);
                ResultSet resultSet = resultSet();
                when(resultSet.getLong(1)).thenReturn(9L);
                return List.of(mapper.mapRow(resultSet, 0));
            });
        assertThat(repository.expireDue(7L, 0)).isEqualTo(1);
        assertThat(repository.find(7L, 9L)).isNotNull();
    }

    @Test
    void mapsNullTimestampFieldsAndReturnsEmptyFind() throws Exception {
        ResultSet resultSet = resultSet();
        when(resultSet.getTimestamp(anyString())).thenReturn(null);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
            .thenAnswer(invocation -> {
                RowMapper<?> mapper = invocation.getArgument(1);
                return List.of(mapper.mapRow(resultSet, 0));
            });
        assertThat(repository.find(7L, 99L)).isNotNull();
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        assertThat(repository.find(7L, 99L)).isNull();
    }

    private void stubRows(RepositorySuppressionRepository.StoredSuppression stored) {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
            .thenReturn(List.of(stored));
    }

    private List<?> rowsFor(InvocationOnMock invocation, RepositorySuppressionRepository.StoredSuppression stored)
        throws Exception {
        RowMapper<?> mapper = invocation.getArgument(1);
        return List.of(mapper.mapRow(resultSet(), 0));
    }

    private ResultSet resultSet() throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getLong("id")).thenReturn(9L);
        when(resultSet.getLong("tenant_id")).thenReturn(7L);
        when(resultSet.getString("organization")).thenReturn("octocat");
        when(resultSet.getString("repository")).thenReturn("repo");
        when(resultSet.getString("rule_id")).thenReturn("RG-AUTH-001");
        when(resultSet.getString("file_glob")).thenReturn("src/**");
        when(resultSet.getString("symbol")).thenReturn("Auth");
        when(resultSet.getString("reason")).thenReturn("reason");
        when(resultSet.getString("status")).thenReturn("PROPOSED");
        when(resultSet.getString("operator")).thenReturn("alice");
        when(resultSet.getTimestamp("expires_at")).thenReturn(Timestamp.valueOf(now.plusDays(30)));
        when(resultSet.getInt("preview_hit_count")).thenReturn(4);
        when(resultSet.getLong("hit_count")).thenReturn(1L);
        when(resultSet.getTimestamp("last_hit_at")).thenReturn(Timestamp.valueOf(now));
        when(resultSet.getTimestamp("created_at")).thenReturn(Timestamp.valueOf(now));
        when(resultSet.getTimestamp("updated_at")).thenReturn(Timestamp.valueOf(now));
        return resultSet;
    }

    private RepositorySuppressionRepository.StoredSuppression stored(String status) {
        return new RepositorySuppressionRepository.StoredSuppression(
            9L, 7L, "octocat", "repo", "RG-AUTH-001", "src/**", "Auth", "reason", status,
            "alice", now.plusDays(30), 4, 1, now, now, now
        );
    }
}
