package com.repoguard.agent.worker;

import com.repoguard.agent.entity.ChangedFile;
import com.repoguard.agent.review.PullRequestDiff;
import com.repoguard.agent.mapper.ChangedFileMapper;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
class ChangedFileReplacementService {

    private final ChangedFileMapper changedFileMapper;
    private final ChangedFileEntityMapper changedFileEntityMapper;
    private final MapperBatchInserter batchInserter;

    ChangedFileReplacementService(
        ChangedFileMapper changedFileMapper,
        ChangedFileEntityMapper changedFileEntityMapper,
        MapperBatchInserter batchInserter
    ) {
        this.changedFileMapper = changedFileMapper;
        this.changedFileEntityMapper = changedFileEntityMapper;
        this.batchInserter = batchInserter;
    }

    void replace(Long taskId, PullRequestDiff diff) {
        replace(taskId, null, diff);
    }

    void replace(Long taskId, Long attemptId, PullRequestDiff diff) {
        List<ChangedFile> entities = diff.files().stream()
            .map(file -> changedFileEntityMapper.toEntity(taskId, attemptId, file))
            .toList();
        batchInserter.insertAll(ChangedFileMapper.class, entities);
    }
}
