package com.repoguard.agent.service.impl;

import com.repoguard.agent.config.WorkerRuntimeEnabled;
import com.repoguard.agent.entity.GithubCommentPublicationBatch;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@WorkerRuntimeEnabled
class GithubCommentPublicationBatchRecoveryWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(GithubCommentPublicationBatchRecoveryWorker.class);
    private static final long CLAIM_TIMEOUT_MS = 30 * 60 * 1000L;

    private final GithubCommentPublicationRecorder publicationRecorder;
    private final GithubCommentPublishServiceImpl publishService;
    private final int batchSize;

    GithubCommentPublicationBatchRecoveryWorker(
        GithubCommentPublicationRecorder publicationRecorder,
        GithubCommentPublishServiceImpl publishService,
        @Value("${app.github.comment-publish.recovery-batch-size:20}") int batchSize
    ) {
        this.publicationRecorder = publicationRecorder;
        this.publishService = publishService;
        this.batchSize = Math.max(1, batchSize);
    }

    @Scheduled(fixedDelayString = "${app.github.comment-publish.recovery-interval-ms:60000}")
    public void recoverPublishBatches() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime runningExpiredBefore = now.minusNanos(CLAIM_TIMEOUT_MS * 1_000_000);
        List<GithubCommentPublicationBatch> batches = publicationRecorder.findRecoverableBatches(
            now,
            runningExpiredBefore,
            batchSize
        );
        for (GithubCommentPublicationBatch batch : batches) {
            LOGGER.info(
                "GitHub comment publish recovery dispatching batch. taskId={}, batchId={}, status={}",
                batch.getTaskId(),
                batch.getId(),
                batch.getStatus()
            );
            publishService.dispatchRecoverableBatch(batch);
        }
    }
}
