package com.repoguard.agent.service.impl;

import com.repoguard.agent.dto.GithubCommentPublicationBatchDto;
import com.repoguard.agent.dto.GithubCommentPublicationHistoryItem;
import com.repoguard.agent.entity.GithubCommentPublicationBatch;
import com.repoguard.agent.entity.GithubCommentPublicationBatchItem;
import com.repoguard.agent.github.GithubWritebackFailureClassifier;
import com.repoguard.agent.github.GithubWritebackFailureClassifier.FailureSummary;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Converts GitHub comment publication history entities into API response DTOs.
 */
@Component
public class GithubCommentPublicationHistoryAssembler {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final GithubWritebackFailureClassifier failureClassifier;

    public GithubCommentPublicationHistoryAssembler(GithubWritebackFailureClassifier failureClassifier) {
        this.failureClassifier = failureClassifier;
    }

    public GithubCommentPublicationBatchDto assembleBatch(
        GithubCommentPublicationBatch batch,
        List<GithubCommentPublicationBatchItem> items
    ) {
        return new GithubCommentPublicationBatchDto(
            batch.getId(),
            batch.getStatus(),
            batch.getTotalFindings(),
            batch.getAttemptedCount(),
            batch.getSucceededCount(),
            batch.getFailedCount(),
            batch.getSkippedCount(),
            format(batch.getCreatedAt()),
            format(batch.getCompletedAt()),
            items.stream().map(this::assembleItem).toList()
        );
    }

    public GithubCommentPublicationHistoryItem assembleItem(GithubCommentPublicationBatchItem item) {
        FailureSummary failureSummary = failureClassifier.classify(
            item.getStatus(),
            item.getSuccess(),
            item.getMessage()
        );
        return new GithubCommentPublicationHistoryItem(
            item.getFindingId(),
            item.getFilePath(),
            item.getLineNumber(),
            item.getTargetType(),
            item.getSuccess(),
            item.getStatus(),
            item.getMessage(),
            failureSummary.category(),
            failureSummary.reason(),
            failureSummary.suggestion(),
            item.getGithubUrl(),
            item.getGithubCommentId(),
            format(item.getPublishedAt())
        );
    }

    private String format(LocalDateTime value) {
        return value == null ? null : value.format(DATE_TIME_FORMATTER);
    }
}
