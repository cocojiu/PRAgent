package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.entity.GithubCommentPublicationBatch;
import com.repoguard.agent.entity.GithubCommentPublicationBatchItem;
import com.repoguard.agent.github.GithubWritebackFailureClassifier;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class GithubCommentPublicationHistoryAssemblerTest {

    private final GithubCommentPublicationHistoryAssembler assembler =
        new GithubCommentPublicationHistoryAssembler(new GithubWritebackFailureClassifier());

    @Test
    void assembleBatchFormatsCountsTimesAndNestedItems() {
        var result = assembler.assembleBatch(batch(), List.of(successItem()));

        assertThat(result.batchId()).isEqualTo(10L);
        assertThat(result.status()).isEqualTo("completed");
        assertThat(result.totalFindings()).isEqualTo(2);
        assertThat(result.attemptedCount()).isEqualTo(2);
        assertThat(result.succeededCount()).isEqualTo(1);
        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(result.skippedCount()).isZero();
        assertThat(result.createdAt()).isEqualTo("2026-06-20 09:50:00");
        assertThat(result.completedAt()).isEqualTo("2026-06-20 09:50:03");
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().publishedAt()).isEqualTo("2026-06-20 09:50:02");
    }

    @Test
    void assembleItemAddsReadableFailureSummary() {
        var result = assembler.assembleItem(failedItem());

        assertThat(result.success()).isFalse();
        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.failureCategory()).isEqualTo("github_token_invalid");
        assertThat(result.failureReason()).isEqualTo("GitHub Token 无效或已过期");
        assertThat(result.failureSuggestion()).contains("更新 GitHub Token");
        assertThat(result.publishedAt()).isNull();
    }

    private GithubCommentPublicationBatch batch() {
        GithubCommentPublicationBatch batch = new GithubCommentPublicationBatch();
        batch.setId(10L);
        batch.setStatus("completed");
        batch.setTotalFindings(2);
        batch.setAttemptedCount(2);
        batch.setSucceededCount(1);
        batch.setFailedCount(1);
        batch.setSkippedCount(0);
        batch.setCreatedAt(LocalDateTime.of(2026, 6, 20, 9, 50));
        batch.setCompletedAt(LocalDateTime.of(2026, 6, 20, 9, 50, 3));
        return batch;
    }

    private GithubCommentPublicationBatchItem successItem() {
        GithubCommentPublicationBatchItem item = new GithubCommentPublicationBatchItem();
        item.setFindingId(1L);
        item.setFilePath("README.md");
        item.setLineNumber(2);
        item.setTargetType("line");
        item.setSuccess(true);
        item.setStatus("published");
        item.setMessage("GitHub comment published");
        item.setGithubUrl("https://github.com/comment/1");
        item.setGithubCommentId(101L);
        item.setPublishedAt(LocalDateTime.of(2026, 6, 20, 9, 50, 2));
        return item;
    }

    private GithubCommentPublicationBatchItem failedItem() {
        GithubCommentPublicationBatchItem item = successItem();
        item.setSuccess(false);
        item.setStatus("failed");
        item.setMessage("401 Bad credentials");
        item.setGithubUrl(null);
        item.setGithubCommentId(null);
        item.setPublishedAt(null);
        return item;
    }
}
