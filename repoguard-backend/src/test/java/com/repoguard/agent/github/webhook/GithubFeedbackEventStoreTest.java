package com.repoguard.agent.github.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.tenancy.TenantContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

class GithubFeedbackEventStoreTest {
    @Test
    void duplicateDeliveriesAreIdempotentAndNeverStoreRawBodies() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var store = new GithubFeedbackEventStore(jdbc);
        var command = GithubFeedbackCommand.parse(GithubFeedbackServiceTest.payload());
        when(jdbc.update(contains("insert into github_feedback_event"), any(Object[].class)))
            .thenReturn(1).thenThrow(new DuplicateKeyException("duplicate"));
        try (TenantContext.Scope _ = TenantContext.withTenant(42L)) {
            assertThat(store.insert("delivery", command, new GithubFeedbackEventStore.Target(2, 3, 4, null, null))).isTrue();
            assertThat(store.insert("delivery", command, new GithubFeedbackEventStore.Target(2, 3, 4, null, null))).isFalse();
        }
        var values = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, org.mockito.Mockito.times(2)).update(contains("insert into github_feedback_event"), values.capture());
        assertThat(values.getValue()).startsWith(42L, "delivery").doesNotContain("/repoguard false-positive reason");
    }

    @Test
    void retriesAreTenantScopedAndCannotReplayAppliedEvents() {
        var jdbc = mock(JdbcTemplate.class);
        var store = new GithubFeedbackEventStore(jdbc);
        when(jdbc.update(contains("status = 'FAILED'"), any(Object[].class))).thenReturn(0);
        try (TenantContext.Scope _ = TenantContext.withTenant(42L)) {
            assertThat(store.retry(7)).isFalse();
            store.retryLater(7);
            store.finish(7, "APPLIED", null);
        }
        verify(jdbc).update(contains("and status = 'FAILED'"), org.mockito.ArgumentMatchers.eq(new Object[] {42L, 7L}));
        verify(jdbc).update(contains("if(attempts >= 4"), org.mockito.ArgumentMatchers.eq(new Object[] {42L, 7L}));
        verify(jdbc).update(contains("and status = 'PENDING'"), org.mockito.ArgumentMatchers.eq(new Object[] {"APPLIED", null, 42L, 7L}));
    }
}
