package com.repoguard.agent.scm;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.review.PullRequestDiff;
import com.repoguard.agent.service.ScmProviderService;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** Coordinates SCM adapter calls and keeps persistence concerns out of controllers. */
@Service
public class ScmProviderServiceImpl implements ScmProviderService {

    private final ScmProviderRegistry providerRegistry;
    private final ReviewTaskMapper reviewTaskMapper;

    public ScmProviderServiceImpl(ScmProviderRegistry providerRegistry, ReviewTaskMapper reviewTaskMapper) {
        this.providerRegistry = Objects.requireNonNull(providerRegistry, "providerRegistry");
        this.reviewTaskMapper = Objects.requireNonNull(reviewTaskMapper, "reviewTaskMapper");
    }

    @Override
    public List<ScmProviderRegistry.ScmProviderDescriptor> providers() {
        return providerRegistry.descriptors();
    }

    @Override
    public List<ScmChangeRequestSummary> changeRequests(String provider) {
        return providerRegistry.require(provider).listOpenChangeRequests();
    }

    @Override
    public PullRequestDiff diff(String provider, Long taskId) {
        return provider(provider).fetchPullRequestDiff(task(taskId));
    }

    @Override
    public Map<String, String> head(String provider, Long taskId) {
        ReviewTask task = task(taskId);
        ScmProvider scmProvider = provider(provider);
        return Map.of(
            "provider", Objects.toString(scmProvider.providerKey(), ""),
            "sha", Objects.toString(scmProvider.fetchPullRequestHeadSha(task), "")
        );
    }

    @Override
    public ScmCommentResult comment(String provider, Long taskId, ScmCommentDraft draft) {
        return provider(provider).publishComment(task(taskId), draft);
    }

    @Override
    public ScmStatusResult status(String provider, Long taskId, ScmStatusRequest request) {
        return provider(provider).publishStatus(task(taskId), request);
    }

    private ScmProvider provider(String key) {
        return providerRegistry.require(key);
    }

    private ReviewTask task(Long taskId) {
        ReviewTask task = taskId == null ? null : reviewTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "Review task not found: " + taskId);
        }
        return task;
    }
}
