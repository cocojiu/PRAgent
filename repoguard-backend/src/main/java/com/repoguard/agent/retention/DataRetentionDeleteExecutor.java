package com.repoguard.agent.retention;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.entity.ChangedFile;
import com.repoguard.agent.entity.GithubCommentPublication;
import com.repoguard.agent.entity.GithubCommentPublicationBatch;
import com.repoguard.agent.entity.GithubCommentPublicationBatchItem;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.entity.ReviewTimeline;
import com.repoguard.agent.mapper.ChangedFileMapper;
import com.repoguard.agent.mapper.GithubCheckRunMapper;
import com.repoguard.agent.mapper.GithubCommentPublicationBatchItemMapper;
import com.repoguard.agent.mapper.GithubCommentPublicationBatchMapper;
import com.repoguard.agent.mapper.GithubCommentPublicationMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.ReviewTimelineMapper;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;

@Component
public class DataRetentionDeleteExecutor {

    private final ChangedFileMapper changedFileMapper;
    private final ReviewFindingMapper reviewFindingMapper;
    private final ReviewTimelineMapper reviewTimelineMapper;
    private final GithubCommentPublicationMapper githubCommentPublicationMapper;
    private final GithubCommentPublicationBatchMapper githubCommentPublicationBatchMapper;
    private final GithubCommentPublicationBatchItemMapper githubCommentPublicationBatchItemMapper;
    private final ReviewTaskMapper reviewTaskMapper;
    private final GithubCheckRunMapper githubCheckRunMapper;

    @Autowired
    public DataRetentionDeleteExecutor(
        ChangedFileMapper changedFileMapper,
        ReviewFindingMapper reviewFindingMapper,
        ReviewTimelineMapper reviewTimelineMapper,
        GithubCommentPublicationMapper githubCommentPublicationMapper,
        GithubCommentPublicationBatchMapper githubCommentPublicationBatchMapper,
        GithubCommentPublicationBatchItemMapper githubCommentPublicationBatchItemMapper,
        ReviewTaskMapper reviewTaskMapper,
        ObjectProvider<GithubCheckRunMapper> githubCheckRunMapperProvider
    ) {
        this(
            changedFileMapper, reviewFindingMapper, reviewTimelineMapper, githubCommentPublicationMapper,
            githubCommentPublicationBatchMapper, githubCommentPublicationBatchItemMapper, reviewTaskMapper,
            githubCheckRunMapperProvider.getIfAvailable()
        );
    }

    public DataRetentionDeleteExecutor(
        ChangedFileMapper changedFileMapper,
        ReviewFindingMapper reviewFindingMapper,
        ReviewTimelineMapper reviewTimelineMapper,
        GithubCommentPublicationMapper githubCommentPublicationMapper,
        GithubCommentPublicationBatchMapper githubCommentPublicationBatchMapper,
        GithubCommentPublicationBatchItemMapper githubCommentPublicationBatchItemMapper,
        ReviewTaskMapper reviewTaskMapper
    ) {
        this(
            changedFileMapper, reviewFindingMapper, reviewTimelineMapper, githubCommentPublicationMapper,
            githubCommentPublicationBatchMapper, githubCommentPublicationBatchItemMapper, reviewTaskMapper,
            (GithubCheckRunMapper) null
        );
    }

    private DataRetentionDeleteExecutor(
        ChangedFileMapper changedFileMapper,
        ReviewFindingMapper reviewFindingMapper,
        ReviewTimelineMapper reviewTimelineMapper,
        GithubCommentPublicationMapper githubCommentPublicationMapper,
        GithubCommentPublicationBatchMapper githubCommentPublicationBatchMapper,
        GithubCommentPublicationBatchItemMapper githubCommentPublicationBatchItemMapper,
        ReviewTaskMapper reviewTaskMapper,
        GithubCheckRunMapper githubCheckRunMapper
    ) {
        this.changedFileMapper = Objects.requireNonNull(changedFileMapper, "changedFileMapper");
        this.reviewFindingMapper = Objects.requireNonNull(reviewFindingMapper, "reviewFindingMapper");
        this.reviewTimelineMapper = Objects.requireNonNull(reviewTimelineMapper, "reviewTimelineMapper");
        this.githubCommentPublicationMapper = Objects.requireNonNull(
            githubCommentPublicationMapper,
            "githubCommentPublicationMapper"
        );
        this.githubCommentPublicationBatchMapper = Objects.requireNonNull(
            githubCommentPublicationBatchMapper,
            "githubCommentPublicationBatchMapper"
        );
        this.githubCommentPublicationBatchItemMapper = Objects.requireNonNull(
            githubCommentPublicationBatchItemMapper,
            "githubCommentPublicationBatchItemMapper"
        );
        this.reviewTaskMapper = Objects.requireNonNull(reviewTaskMapper, "reviewTaskMapper");
        this.githubCheckRunMapper = githubCheckRunMapper;
    }

    public DeletionResult delete(List<Long> taskIds) {
        List<Long> immutableTaskIds = List.copyOf(Objects.requireNonNull(taskIds, "taskIds"));
        if (immutableTaskIds.isEmpty()) {
            throw new IllegalArgumentException("taskIds must not be empty");
        }
        int deletedBatchItems = githubCommentPublicationBatchItemMapper.delete(
            new LambdaQueryWrapper<GithubCommentPublicationBatchItem>()
                .in(GithubCommentPublicationBatchItem::getTaskId, immutableTaskIds)
        );
        int deletedPublications = githubCommentPublicationMapper.delete(
            new LambdaQueryWrapper<GithubCommentPublication>()
                .in(GithubCommentPublication::getTaskId, immutableTaskIds)
        );
        int deletedBatches = githubCommentPublicationBatchMapper.delete(
            new LambdaQueryWrapper<GithubCommentPublicationBatch>()
                .in(GithubCommentPublicationBatch::getTaskId, immutableTaskIds)
        );
        int deletedChangedFiles = changedFileMapper.delete(
            new LambdaQueryWrapper<ChangedFile>().in(ChangedFile::getTaskId, immutableTaskIds)
        );
        int deletedTimelines = reviewTimelineMapper.delete(
            new LambdaQueryWrapper<ReviewTimeline>().in(ReviewTimeline::getTaskId, immutableTaskIds)
        );
        int deletedFindings = reviewFindingMapper.delete(
            new LambdaQueryWrapper<ReviewFinding>().in(ReviewFinding::getTaskId, immutableTaskIds)
        );
        if (githubCheckRunMapper != null) {
            githubCheckRunMapper.delete(
                new LambdaQueryWrapper<com.repoguard.agent.entity.GithubCheckRun>()
                    .in(com.repoguard.agent.entity.GithubCheckRun::getTaskId, immutableTaskIds)
            );
        }
        int deletedTasks = reviewTaskMapper.delete(
            new LambdaQueryWrapper<ReviewTask>().in(ReviewTask::getId, immutableTaskIds)
        );
        return new DeletionResult(
            deletedBatchItems,
            deletedPublications,
            deletedBatches,
            deletedChangedFiles,
            deletedTimelines,
            deletedFindings,
            deletedTasks
        );
    }

    public record DeletionResult(
        int deletedBatchItems,
        int deletedPublications,
        int deletedBatches,
        int deletedChangedFiles,
        int deletedTimelines,
        int deletedFindings,
        int deletedTasks
    ) {
    }
}
