package com.repoguard.agent.service.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.entity.GithubCommentPublicationBatch;
import java.util.List;
import org.junit.jupiter.api.Test;

class GithubCommentPublicationBatchRecoveryWorkerTest {

    private final GithubCommentPublicationRecorder publicationRecorder =
        org.mockito.Mockito.mock(GithubCommentPublicationRecorder.class);
    private final GithubCommentPublishServiceImpl publishService =
        org.mockito.Mockito.mock(GithubCommentPublishServiceImpl.class);

    @Test
    void dispatchesRecoverableBatchesThroughPublishService() {
        GithubCommentPublicationBatch batch = new GithubCommentPublicationBatch();
        batch.setId(99L);
        batch.setTaskId(521L);
        batch.setStatus("queued");
        when(publicationRecorder.findRecoverableBatches(any(), any(), eq(2))).thenReturn(List.of(batch));

        new GithubCommentPublicationBatchRecoveryWorker(publicationRecorder, publishService, 2)
            .recoverPublishBatches();

        verify(publishService).dispatchRecoverableBatch(batch);
    }
}
