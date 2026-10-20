package com.repoguard.agent.github.comment;

import com.repoguard.agent.dto.GithubCommentPublishItem;
import com.repoguard.agent.entity.GithubCommentPublication;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.github.GithubPullRequestClient;
import com.repoguard.agent.github.GithubReviewCommentDraft;
import com.repoguard.agent.github.GithubReviewCommentResult;
import com.repoguard.agent.github.GithubWritebackFailureClassifier;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class GithubCommentDraftPublisher {
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final GithubPullRequestClient client;
    private final GithubWritebackFailureClassifier classifier;
    private final GithubPublicationProtocol protocol;
    public GithubCommentDraftPublisher(GithubPullRequestClient client, GithubPublicationProtocol protocol,
        GithubWritebackFailureClassifier classifier) {
        this.client = client; this.classifier = classifier; this.protocol = protocol;
    }
    public List<GithubCommentPublishItem> publish(ReviewTask task, List<GithubReviewCommentDraft> drafts) {
        if (drafts.isEmpty()) return List.of();
        var claims = protocol.claim(task, drafts);
        var accepted = claims.stream().filter(GithubPublicationProtocol.Claim::granted).toList();
        List<GithubReviewCommentResult> results;
        try {
            results = accepted.isEmpty() ? List.of() : client.publishPullRequestComments(task,
                accepted.stream().map(GithubPublicationProtocol.Claim::markedDraft).toList());
        } catch (RuntimeException failure) {
            // A top-level failure can occur after some POSTs; never downgrade missing outcomes to retryable failure.
            results = List.of();
        }
        List<GithubCommentPublishItem> items = new ArrayList<>();
        for (var claim : claims) {
            var draft = claim.draft();
            var publication = claim.publication();
            if (claim.granted()) {
                var result = results.stream().filter(value -> java.util.Objects.equals(value.findingId(), draft.findingId()))
                    .findFirst().orElse(new GithubReviewCommentResult(draft.findingId(), draft.path(), draft.line(),
                        draft.targetType(), false, "outcome_unknown", "GitHub 回写结果待核验，已阻止再次发布。", null, null));
                publication = protocol.finish(claim, result);
            }
            var failure = classifier.classify(publication.getStatus(), publication.getSuccess(), publication.getMessage());
            items.add(new GithubCommentPublishItem(draft.findingId(), draft.path(), draft.line(), publication.getTargetType(),
                publication.getSuccess(), publication.getStatus(), publication.getMessage(), failure.category(), failure.reason(),
                failure.suggestion(), publication.getGithubUrl(), publication.getGithubCommentId(), publication.getPublishedAt() == null
                    ? null : publication.getPublishedAt().format(DATE_TIME_FORMATTER)));
        }
        return List.copyOf(items);
    }
}
