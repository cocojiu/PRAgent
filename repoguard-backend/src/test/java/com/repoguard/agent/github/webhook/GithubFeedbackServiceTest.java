package com.repoguard.agent.github.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.repoguard.agent.service.FindingFeedbackService;
import com.repoguard.agent.tenancy.TenantContext;
import com.repoguard.agent.tenancy.TenantRepositoryBinding;
import com.repoguard.agent.tenancy.TenantRepositoryResolver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class GithubFeedbackServiceTest {
    private final GithubWebhookProperties properties = new GithubWebhookProperties();
    private final TenantRepositoryResolver tenants = mock(TenantRepositoryResolver.class);
    private final GithubFeedbackEventStore store = mock(GithubFeedbackEventStore.class);
    private final GithubFeedbackVerifier verifier = mock(GithubFeedbackVerifier.class);
    private final FindingFeedbackService feedback = mock(FindingFeedbackService.class);
    private final PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
    private final GithubFeedbackService service = new GithubFeedbackService(properties, tenants, store, verifier,
        feedback, tx, new SimpleMeterRegistry());

    @Test
    void parsesOnlyExplicitHumanRepliesAndSanitizesNotes() throws Exception {
        ObjectNode root = payload();
        assertThat(GithubFeedbackCommand.parse(root).feedbackStatus()).isEqualTo("false_positive");
        ((ObjectNode) root.path("comment")).put("body", "/repoguard ignore password=example");
        assertThat(GithubFeedbackCommand.parse(root).note()).doesNotContain("example");
        for (String body : List.of("please ignore", "/repoguard fixed", "/repoguard ignore", "/repoguard ignore why\nmore")) {
            ((ObjectNode) root.path("comment")).put("body", body);
            assertThat(GithubFeedbackCommand.parse(root)).isNull();
        }
        root = payload();
        ((ObjectNode) root.path("comment").path("user")).put("type", "Bot");
        assertThat(GithubFeedbackCommand.parse(root)).isNull();
        root = payload();
        root.put("action", "edited");
        assertThat(GithubFeedbackCommand.parse(root)).isNull();
        root = payload();
        ((ObjectNode) root.path("comment")).remove("in_reply_to_id");
        assertThat(GithubFeedbackCommand.parse(root)).isNull();
    }

    @Test
    void disabledAndUnlistedRepositoriesNeverWriteOrCallGithub() throws Exception {
        assertThat(service.receive(payload(), "delivery-1").status()).isEqualTo("skipped");
        properties.setFeedbackEnabled(true);
        assertThat(service.receive(payload(), "delivery-1").status()).isEqualTo("skipped");
        properties.setAllowedRepositories(List.of("org/repo"));
        properties.setRequireSignature(false);
        assertThat(service.receive(payload(), "delivery-1").status()).isEqualTo("skipped");
        verifyNoInteractions(store, verifier, feedback);
    }

    @Test
    void receivesInResolvedTenantAndDeduplicatesWithoutApplyingFeedback() throws Exception {
        enable();
        when(tenants.resolve("org", "repo", null)).thenReturn(new TenantRepositoryBinding(42L, "tenant", "org", "repo", null));
        when(store.target(any(), eq(false))).thenAnswer(invocation -> {
            assertThat(TenantContext.currentTenantIdOrDefault()).isEqualTo(42L);
            return target(null);
        });
        when(store.insert(anyString(), any(), any())).thenReturn(true, false);
        assertThat(service.receive(payload(), "delivery-1").existing()).isFalse();
        assertThat(service.receive(payload(), "delivery-1").existing()).isTrue();
        assertThat(TenantContext.hasTenant()).isFalse();
        verifyNoInteractions(verifier, feedback);
    }

    @Test
    void appliesOnlyOnceAfterVerificationAndCurrentAttemptCheck() throws Exception {
        enable();
        var command = GithubFeedbackCommand.parse(payload());
        var event = new GithubFeedbackEventStore.Event(1, command, 2, 3, 4, "PENDING");
        when(store.due()).thenReturn(List.of(event));
        when(store.lock(1)).thenReturn(event, new GithubFeedbackEventStore.Event(1, command, 2, 3, 4, "APPLIED"));
        when(store.target(command, true)).thenReturn(target(null));
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service.processDue();
        service.processDue();
        verify(feedback, times(1)).updateFindingFeedback(eq(2L), eq(3L), argThat(r -> r.status().equals("false_positive")), eq("github:alice"));
        verify(store).finish(1, "APPLIED", null);
    }

    @Test
    void rejectsPermissionsStaleAttemptsAndNewerManualFeedback() throws Exception {
        enable();
        var c = GithubFeedbackCommand.parse(payload());
        var event = new GithubFeedbackEventStore.Event(1, c, 2, 3, 4, "PENDING");
        when(store.due()).thenReturn(List.of(event));
        when(store.lock(1)).thenReturn(event);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(verifier.rejection(c)).thenReturn("ACTOR_PERMISSION_DENIED", null, null);
        when(store.target(c, true)).thenReturn(null, target(c.createdAt().plusSeconds(1)));
        service.processDue(); service.processDue(); service.processDue();
        verify(store).finish(1, "IGNORED", "ACTOR_PERMISSION_DENIED");
        verify(store).finish(1, "IGNORED", "STALE_PUBLICATION");
        verify(store).finish(1, "IGNORED", "NEWER_FEEDBACK_EXISTS");
        verifyNoInteractions(feedback);
    }

    @Test
    void externalFailureLeavesVisibleBoundedRetry() throws Exception {
        enable();
        var c = GithubFeedbackCommand.parse(payload());
        when(store.due()).thenReturn(List.of(new GithubFeedbackEventStore.Event(1, c, 2, 3, 4, "PENDING")));
        when(verifier.rejection(c)).thenThrow(new IllegalStateException("unavailable"));
        service.processDue();
        verify(store).retryLater(1);
        verifyNoInteractions(feedback);
    }

    private void enable() {
        properties.setFeedbackEnabled(true); properties.setAllowedRepositories(List.of("org/repo"));
        when(tenants.resolve("org", "repo", null)).thenReturn(new TenantRepositoryBinding(1L, "default", "org", "repo", null));
    }
    private GithubFeedbackEventStore.Target target(java.time.LocalDateTime feedbackAt) {
        return new GithubFeedbackEventStore.Target(2, 3, 4, feedbackAt, "unreviewed");
    }
    static ObjectNode payload() throws Exception {
        return (ObjectNode) new ObjectMapper().readTree("""
            {"action":"created","repository":{"owner":{"login":"org"},"name":"repo"},
             "pull_request":{"number":7,"head":{"sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}},
             "sender":{"id":10},"comment":{"id":20,"in_reply_to_id":30,
             "user":{"id":10,"login":"alice","type":"User"},"body":"/repoguard false-positive reason",
             "commit_id":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","created_at":"2026-09-16T00:00:00Z"}}
            """);
    }
}
