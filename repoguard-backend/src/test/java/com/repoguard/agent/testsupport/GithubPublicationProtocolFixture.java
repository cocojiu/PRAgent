package com.repoguard.agent.testsupport;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.repoguard.agent.entity.GithubCommentPublication;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.github.GithubPublicationIntent;
import com.repoguard.agent.github.GithubReviewCommentDraft;
import com.repoguard.agent.github.GithubReviewCommentResult;
import com.repoguard.agent.github.comment.GithubCommentPublicationRecorder;
import com.repoguard.agent.github.comment.GithubPublicationProtocol;
import java.util.List;

/** Adapts pre-existing orchestration unit fixtures; actual durability has independent integration coverage. */
public final class GithubPublicationProtocolFixture {
    private GithubPublicationProtocolFixture() { }
    public static GithubPublicationProtocol protocol(GithubCommentPublicationRecorder recorder) {
        var protocol = mock(GithubPublicationProtocol.class);
        when(protocol.claim(any(), any())).thenAnswer(call -> {
            ReviewTask task = call.getArgument(0);
            List<GithubReviewCommentDraft> drafts = call.getArgument(1);
            return drafts.stream().map(draft -> {
                var publication = new GithubCommentPublication();
                publication.setTaskId(task.getId()); publication.setFindingId(draft.findingId());
                String marker = GithubPublicationIntent.create(task.getCommitSha(), draft.body());
                return new GithubPublicationProtocol.Claim(task.getId(), draft, publication, marker);
            }).toList();
        });
        when(protocol.finish(any(), any())).thenAnswer(call -> {
            GithubPublicationProtocol.Claim claim = call.getArgument(0);
            GithubReviewCommentResult result = call.getArgument(1);
            var publication = recorder.recordPublication(claim.taskId(), result);
            publication.setStatus(result.status()); publication.setSuccess(result.success()); publication.setMessage(result.message());
            publication.setTargetType(result.targetType()); publication.setGithubUrl(result.url()); publication.setGithubCommentId(result.commentId());
            return publication;
        });
        return protocol;
    }
}
