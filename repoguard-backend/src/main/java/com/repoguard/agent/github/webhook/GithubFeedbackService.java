package com.repoguard.agent.github.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.repoguard.agent.dto.FindingFeedbackRequest;
import com.repoguard.agent.service.FindingFeedbackService;
import com.repoguard.agent.tenancy.ScheduledJobLeaseContext;
import com.repoguard.agent.tenancy.TenantContext;
import com.repoguard.agent.tenancy.TenantRepositoryResolver;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class GithubFeedbackService {
    private final GithubWebhookProperties properties;
    private final TenantRepositoryResolver tenants;
    private final GithubFeedbackEventStore store;
    private final GithubFeedbackVerifier verifier;
    private final FindingFeedbackService feedback;
    private final TransactionTemplate transactions;
    private final MeterRegistry metrics;

    public GithubFeedbackService(GithubWebhookProperties properties, TenantRepositoryResolver tenants,
        GithubFeedbackEventStore store, GithubFeedbackVerifier verifier, FindingFeedbackService feedback,
        org.springframework.transaction.PlatformTransactionManager transactionManager, MeterRegistry metrics) {
        this.properties = properties;
        this.tenants = tenants;
        this.store = store;
        this.verifier = verifier;
        this.feedback = feedback;
        this.transactions = new TransactionTemplate(transactionManager);
        this.metrics = metrics;
    }

    public GithubWebhookResponse receive(JsonNode root, String deliveryId) {
        if (!enabled() || deliveryId == null || !deliveryId.matches("[A-Za-z0-9-]{1,128}")) return skipped(deliveryId);
        GithubFeedbackCommand command = GithubFeedbackCommand.parse(root);
        if (command == null || !allowed(command)) return skipped(deliveryId);
        var binding = tenants.resolve(command.owner(), command.repository(), command.installationId());
        try (TenantContext.Scope _ = TenantContext.withTenant(binding.tenantId())) {
            var target = store.target(command, false);
            if (target == null) return skipped(deliveryId);
            boolean created = store.insert(deliveryId, command, target);
            return new GithubWebhookResponse("accepted", "Feedback queued for verification", target.taskId(),
                !created, deliveryId, "created");
        }
    }

    public void processDue() {
        if (!enabled()) return;
        for (var candidate : store.due()) {
            ScheduledJobLeaseContext.assertHeld();
            try {
                if (!allowed(candidate.command())) {
                    store.finish(candidate.id(), "IGNORED", "REPOSITORY_DISABLED");
                    continue;
                }
                var binding = tenants.resolve(candidate.command().owner(), candidate.command().repository(), null);
                if (binding.tenantId() != TenantContext.currentTenantIdOrDefault()) {
                    store.finish(candidate.id(), "IGNORED", "TENANT_BINDING_CHANGED");
                    continue;
                }
                String rejection = verifier.rejection(candidate.command());
                transactions.executeWithoutResult(ignored -> apply(candidate, rejection));
            } catch (RuntimeException ex) {
                ScheduledJobLeaseContext.assertHeld();
                store.retryLater(candidate.id());
                metrics.counter("repoguard.github.feedback", "outcome", "retry").increment();
            }
        }
    }

    private void apply(GithubFeedbackEventStore.Event candidate, String rejection) {
        ScheduledJobLeaseContext.assertHeld();
        var event = store.lock(candidate.id());
        if (event == null || !"PENDING".equals(event.status())) return;
        if (!enabled()) return;
        if (rejection == null) {
            var target = store.target(event.command(), true);
            if (target == null || target.taskId() != event.taskId() || target.findingId() != event.findingId()
                || target.attemptId() != event.attemptId()) rejection = "STALE_PUBLICATION";
            else if (target.feedbackAt() != null && !target.feedbackAt().isBefore(event.command().createdAt())) {
                rejection = "NEWER_FEEDBACK_EXISTS";
            } else if (event.command().feedbackStatus().equalsIgnoreCase(target.feedbackStatus())) rejection = "ALREADY_APPLIED";
        }
        if (rejection != null) {
            store.finish(event.id(), "IGNORED", rejection);
            metrics.counter("repoguard.github.feedback", "outcome", "ignored").increment();
            return;
        }
        feedback.updateFindingFeedback(event.taskId(), event.findingId(),
            new FindingFeedbackRequest(event.command().feedbackStatus(), event.command().note()),
            "github:" + event.command().actor());
        ScheduledJobLeaseContext.assertHeld();
        store.finish(event.id(), "APPLIED", null);
        metrics.counter("repoguard.github.feedback", "outcome", "applied").increment();
        metrics.timer("repoguard.github.feedback.delay").record(Duration.ofMillis(Math.max(0,
            Duration.between(event.command().createdAt(), LocalDateTime.now(ZoneId.systemDefault())).toMillis())));
    }

    public boolean enabled() { return properties.isEnabled() && properties.isFeedbackEnabled() && properties.isRequireSignature(); }

    private boolean allowed(GithubFeedbackCommand c) {
        String name = (c.owner() + "/" + c.repository()).toLowerCase(Locale.ROOT);
        return properties.getAllowedRepositories().stream().anyMatch(value -> value != null && name.equals(value.trim().toLowerCase(Locale.ROOT)));
    }

    private GithubWebhookResponse skipped(String delivery) {
        return GithubWebhookResponse.skipped("Feedback is disabled, unsupported or unrelated to a current publication", delivery, null);
    }
}
