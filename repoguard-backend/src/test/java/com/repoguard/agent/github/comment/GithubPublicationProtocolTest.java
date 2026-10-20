package com.repoguard.agent.github.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import com.repoguard.agent.entity.GithubCommentPublication;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.github.*;
import com.repoguard.agent.mapper.GithubCommentPublicationMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class GithubPublicationProtocolTest {
    private final GithubCommentPublicationMapper mapper = mock(GithubCommentPublicationMapper.class);
    private final ReviewTaskMapper tasks = mock(ReviewTaskMapper.class);
    private final GithubCommentPublicationRecorder recorder = mock(GithubCommentPublicationRecorder.class);
    private final AtomicReference<GithubCommentPublication> durable = new AtomicReference<>();
    private final GithubPublicationProtocol protocol = new GithubPublicationProtocol(mapper, tasks, recorder);
    private ReviewTask task() {
        var task = new ReviewTask(); task.setId(1L); task.setCommitSha("a".repeat(40)); return task;
    }
    private GithubReviewCommentDraft draft() { return new GithubReviewCommentDraft(null, null, null, "fixture", "pull_request"); }
    private void fixture() {
        when(tasks.selectGithubPublishTaskForUpdate(1L)).thenReturn(task());
        when(mapper.selectOne(any())).thenAnswer(call -> durable.get());
        when(recorder.recordPublication(any(), any())).thenAnswer(call -> {
            GithubReviewCommentResult value = call.getArgument(1);
            var publication = new GithubCommentPublication(); publication.setId(42L); publication.setTaskId(1L);
            publication.setStatus(value.status()); publication.setSuccess(value.success()); publication.setMessage(value.message());
            publication.setTargetType(value.targetType()); durable.set(publication); return publication;
        });
    }
    @Test
    void intentExistsBeforeNetworkAndAnotherPublisherCannotClaimIt() {
        fixture();
        var client = mock(GithubPullRequestClient.class);
        when(client.publishPullRequestComments(any(), any())).thenAnswer(call -> {
            assertThat(durable.get().getStatus()).isEqualTo("outcome_unknown");
            List<GithubReviewCommentDraft> sent = call.getArgument(1);
            assertThat(sent.getFirst().body()).contains(GithubPublicationIntent.marker(durable.get().getMessage()));
            throw new IllegalStateException("simulated interruption after transmission");
        });
        when(mapper.update(any())).thenReturn(1);
        var publisher = new GithubCommentDraftPublisher(client, protocol, new GithubWritebackFailureClassifier());
        assertThat(publisher.publish(task(), List.of(draft())).getFirst().status()).isEqualTo("outcome_unknown");
        assertThat(publisher.publish(task(), List.of(draft())).getFirst().status()).isEqualTo("outcome_unknown");
        verify(client, times(1)).publishPullRequestComments(any(), any());
    }
    @Test
    void reloadedProtocolRetainsAnUnfinishedDurableIntent() {
        fixture();
        var first = protocol.claim(task(), List.of(draft())).getFirst();
        var reloaded = new GithubPublicationProtocol(mapper, tasks, recorder);
        assertThat(first.granted()).isTrue();
        assertThat(reloaded.claim(task(), List.of(draft())).getFirst().granted()).isFalse();
        verify(recorder, times(1)).recordPublication(any(), any());
    }
    @Test
    void taskVersionChangeCannotAcquireAnIntent() {
        fixture(); var changed = task(); changed.setCommitSha("b".repeat(40));
        assertThatThrownBy(() -> protocol.claim(changed, List.of(draft()))).hasMessageContaining("commit changed");
        verifyNoInteractions(recorder);
    }
    @Test
    void lateResponseCannotOverwriteAnExplicitClosure() {
        fixture(); var claim = protocol.claim(task(), List.of(draft())).getFirst();
        durable.get().setStatus("closed");
        assertThat(protocol.finish(claim, new GithubReviewCommentResult(null, null, null, "pull_request", true,
            "published", "late result", "https://example.invalid/comment", 1L)).getStatus()).isEqualTo("closed");
        verify(mapper, never()).update(any());
    }
    @Test
    void staleResolutionCannotChangePublicationState() {
        fixture(); protocol.claim(task(), List.of(draft()));
        assertThatThrownBy(() -> protocol.resolve(1L, 42L, "stale", new GithubReviewCommentResult(null, null, null,
            "pull_request", false, "closed", "close", null, null))).hasMessageContaining("changed");
        verify(mapper, never()).update(any());
    }
    @Test
    void oldPublishedOrClosedRecordsCannotBeRearmed() {
        fixture(); var value = new GithubCommentPublication(); value.setStatus("closed"); value.setSuccess(false); durable.set(value);
        assertThat(protocol.claim(task(), List.of(draft())).getFirst().granted()).isFalse();
        value.setStatus("published"); value.setSuccess(true);
        assertThat(protocol.claim(task(), List.of(draft())).getFirst().granted()).isFalse();
        verifyNoInteractions(recorder);
    }
    @Test
    void compatibleClosedIntentBlocksBothOldReadersAndCurrentPublishers() {
        fixture(); var claim = protocol.claim(task(), List.of(draft())).getFirst();
        durable.get().setMessage(GithubPublicationIntent.CLOSED_PREFIX + claim.expectedMessage());
        assertThat(durable.get().getStatus()).isEqualTo("outcome_unknown");
        assertThat(GithubPublicationIntent.pending(durable.get().getStatus(), durable.get().getMessage())).isFalse();
        var client = mock(GithubPullRequestClient.class);
        var publisher = new GithubCommentDraftPublisher(client, protocol, new GithubWritebackFailureClassifier());
        assertThat(publisher.publish(task(), List.of(draft())).getFirst().status()).isEqualTo("closed");
        assertThatThrownBy(() -> protocol.resolve(1L, 42L, GithubPublicationProtocol.version(durable.get()),
            new GithubReviewCommentResult(null, null, null, "pull_request", true, "published", "late", null, null)))
            .hasMessageContaining("changed");
        protocol.finish(claim, new GithubReviewCommentResult(null, null, null, "pull_request", true, "published", "late", null, null));
        verifyNoInteractions(client); verify(mapper, never()).update(any());
    }
    @Test
    void closedProjectionHidesMetadataAndCannotEnterAnotherPublicationPlan() {
        fixture(); var value = protocol.claim(task(), List.of(draft())).getFirst().publication();
        value.setMessage(GithubPublicationIntent.CLOSED_PREFIX + value.getMessage());
        var preview = new GithubCommentPreviewItemBuilder().buildPrSummaryItem(
            mock(com.repoguard.agent.dto.PrReviewSummaryDto.class), value);
        assertThat(preview.commentable()).isFalse(); assertThat(preview.publicationStatus()).isEqualTo("closed");
        assertThat(preview.reason()).contains("不再回写");
        assertThat(preview.publicationMessage()).doesNotContain("<!--");
        assertThat(new GithubCommentPublishPlanBuilder().build(List.of(preview)).drafts()).isEmpty();
    }
    @Test
    void untrustedDiagnosticTextCannotForgeTheAnchoredClosureMetadata() {
        String diagnostic = GithubPublicationIntent.message(GithubPublicationIntent.create("sha", "body"),
            "remote failure containing " + GithubPublicationIntent.CLOSED_PREFIX);
        assertThat(GithubPublicationIntent.pending("outcome_unknown", diagnostic)).isTrue();
        assertThat(GithubPublicationIntent.status("outcome_unknown", null)).isEqualTo("outcome_unknown");
    }

}
