package com.repoguard.agent.github.comment;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.entity.GithubCommentPublication;
import com.repoguard.agent.github.GithubPublicationIntent;
import com.repoguard.agent.github.GithubPublicationVerifier;
import com.repoguard.agent.github.GithubReviewCommentResult;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class GithubPublicationResolutionService {
    private final GithubPublicationProtocol protocol;
    private final GithubPublicationVerifier verifier;
    public GithubPublicationResolutionService(GithubPublicationProtocol protocol, GithubPublicationVerifier verifier) {
        this.protocol = protocol; this.verifier = verifier;
    }
    public List<PendingGithubPublication> list(Long taskId, long afterId, int limit) {
        return protocol.unresolved(taskId, afterId, limit).stream().map(PendingGithubPublication::from).toList();
    }
    public PendingGithubPublication verify(Long taskId, Long publicationId, String version, String kind, long remoteId) {
        var task = protocol.task(taskId);
        var publication = protocol.load(taskId, publicationId);
        if (!"outcome_unknown".equals(publication.getStatus()) || !GithubPublicationProtocol.version(publication).equals(version))
            throw new BusinessException(ErrorCode.CONFLICT, "Publication changed; reload first");
        var evidence = verifier.verify(task, GithubPublicationIntent.marker(publication.getMessage()), kind, remoteId);
        var result = new GithubReviewCommentResult(publication.getFindingId(), null, null, evidence.targetType(), true,
            "published", "已只读核验远端发布身份；未再次发布。", evidence.url(), evidence.id());
        return PendingGithubPublication.from(protocol.resolve(taskId, publicationId, version, result));
    }
    public PendingGithubPublication close(Long taskId, Long publicationId, String version, String reason, boolean confirmNoRetry) {
        if (!confirmNoRetry) throw new BusinessException(ErrorCode.BAD_REQUEST, "必须确认关闭后不再回写；关闭不会撤回已发出的请求。");
        var publication = protocol.load(taskId, publicationId);
        var result = new GithubReviewCommentResult(publication.getFindingId(), null, null, publication.getTargetType(), false,
            "closed", "已人工关闭，不再回写：" + reason.trim(), null, null);
        return PendingGithubPublication.from(protocol.resolve(taskId, publicationId, version, result));
    }
    public record PendingGithubPublication(Long id, Long findingId, String targetType, String status, String version,
        boolean verifiable, String message, String url) {
        static PendingGithubPublication from(GithubCommentPublication value) {
            String message = value.getMessage();
            String marker = GithubPublicationIntent.marker(message);
            return new PendingGithubPublication(value.getId(), value.getFindingId(), value.getTargetType(), value.getStatus(),
                GithubPublicationProtocol.version(value), marker != null, marker == null ? message : message.replace(marker, "").strip(), value.getGithubUrl());
        }
    }
}
