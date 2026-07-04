package com.repoguard.agent.worker;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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

    ReviewFindingReplacementService(
        ReviewFindingMapper reviewFindingMapper,
        ReviewFindingDeduplicator findingDeduplicator,
        ReviewFindingEntityMapper findingEntityMapper
    ) {
        this.reviewFindingMapper = reviewFindingMapper;
        this.findingDeduplicator = Objects.requireNonNull(findingDeduplicator, "findingDeduplicator");
        this.findingEntityMapper = Objects.requireNonNull(findingEntityMapper, "findingEntityMapper");
    }

    int replace(Long taskId, ReviewResult reviewResult) {
        reviewFindingMapper.delete(new LambdaQueryWrapper<ReviewFinding>().eq(ReviewFinding::getTaskId, taskId));
        List<ReviewFindingResult> findings = findingDeduplicator.deduplicate(reviewResult.findings());
        for (ReviewFindingResult findingResult : findings) {
            reviewFindingMapper.insert(findingEntityMapper.toEntity(taskId, findingResult));
        }
        return findings.size();
    }
}
