package com.repoguard.agent.worker;

import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.review.ReviewFindingResult;
import com.repoguard.agent.review.ReviewResult;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
class ReviewFindingReplacementService {

    private final ReviewFindingMapper reviewFindingMapper;
    private final ReviewFindingDeduplicator findingDeduplicator;
    private final ReviewFindingEntityMapper findingEntityMapper;
    private final MapperBatchInserter batchInserter;

    ReviewFindingReplacementService(
        ReviewFindingMapper reviewFindingMapper,
        ReviewFindingDeduplicator findingDeduplicator,
        ReviewFindingEntityMapper findingEntityMapper,
        MapperBatchInserter batchInserter
    ) {
        this.reviewFindingMapper = reviewFindingMapper;
        this.findingDeduplicator = Objects.requireNonNull(findingDeduplicator, "findingDeduplicator");
        this.findingEntityMapper = Objects.requireNonNull(findingEntityMapper, "findingEntityMapper");
        this.batchInserter = Objects.requireNonNull(batchInserter, "batchInserter");
    }

    int replace(Long taskId, ReviewResult reviewResult) {
        return replace(taskId, null, reviewResult);
    }

    int replace(Long taskId, Long attemptId, ReviewResult reviewResult) {
        List<ReviewFindingResult> findings = findingDeduplicator.deduplicate(reviewResult.findings());
        List<ReviewFinding> entities = findings.stream()
            .map(findingResult -> findingEntityMapper.toEntity(taskId, attemptId, findingResult, reviewResult))
            .toList();
        batchInserter.insertAll(ReviewFindingMapper.class, entities);
        return findings.size();
    }
}
