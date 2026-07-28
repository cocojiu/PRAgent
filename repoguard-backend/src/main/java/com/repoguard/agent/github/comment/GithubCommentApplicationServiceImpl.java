package com.repoguard.agent.github.comment;

import com.repoguard.agent.dto.GithubCommentPublicationHistoryResponse;
import com.repoguard.agent.dto.GithubCommentPreviewResponse;
import com.repoguard.agent.dto.GithubCommentPublishResponse;
import com.repoguard.agent.service.GithubCommentApplicationService;
import com.repoguard.agent.service.GithubCommentHistoryQueryService;
import com.repoguard.agent.service.GithubCommentPreviewService;
import com.repoguard.agent.service.GithubCommentPublishService;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class GithubCommentApplicationServiceImpl implements GithubCommentApplicationService {

    private final GithubCommentPreviewService previewService;
    private final GithubCommentPublishService publishService;
    private final GithubCommentHistoryQueryService historyQueryService;

    @Autowired
    public GithubCommentApplicationServiceImpl(
        GithubCommentPreviewService previewService,
        GithubCommentPublishService publishService,
        GithubCommentHistoryQueryService historyQueryService
    ) {
        this.previewService = Objects.requireNonNull(previewService, "previewService");
        this.publishService = Objects.requireNonNull(publishService, "publishService");
        this.historyQueryService = Objects.requireNonNull(historyQueryService, "historyQueryService");
    }

    @Override
    public GithubCommentPreviewResponse getGithubCommentPreview(Long taskId) {
        return previewService.getPreview(taskId);
    }

    @Override
    public GithubCommentPreviewResponse getGithubCommentPreview(
        Long taskId,
        int page,
        int pageSize,
        boolean commentableOnly
    ) {
        return previewService.getPreview(taskId, page, pageSize, commentableOnly);
    }

    @Override
    public GithubCommentPublishResponse publishGithubComments(Long taskId) {
        return publishService.publishGithubComments(taskId);
    }

    @Override
    public GithubCommentPublicationHistoryResponse getGithubCommentPublicationHistory(
        Long taskId,
        int page,
        int pageSize,
        String status
    ) {
        return historyQueryService.getPublicationHistory(taskId, page, pageSize, status);
    }
}
