package com.repoguard.agent.review;

import com.repoguard.agent.dto.FindingSeverityCountsDto;
import com.repoguard.agent.dto.ReviewTaskListItem;
import com.repoguard.agent.dto.ReviewTaskSummary;
import com.repoguard.agent.dto.ReviewTimelineItem;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.entity.ReviewTaskArchiveSummary;
import com.repoguard.agent.review.task.ReviewTaskListItemAssembler;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Pure read adapter: archive metadata never becomes a live task or a write command. */
public final class ReviewTaskArchiveAdapter {
    private final ReviewTaskListItemAssembler items = new ReviewTaskListItemAssembler();

    public ReviewTaskSummary summary(ReviewTaskArchiveSummary archive, ReviewTaskDetailAssembler assembler) {
        return ReviewTaskSummary.fromDetail(assembler.assemble(task(archive), item(archive),
            List.of(), List.of(), List.of(), List.of(), count(archive.getFindingCount()),
            count(archive.getMissingTestCount()), count(archive.getChangedFileCount()),
            FindingSeverityCountsDto.empty()), true, archive.getCleanupBatchId(), archive.getBackupReference(),
            time(archive.getArchivedAt()));
    }

    public ReviewTask task(ReviewTaskArchiveSummary archive) {
        ReviewTask task = new ReviewTask();
        task.setId(archive.getTaskId());
        task.setPrNumber(archive.getPrNumber());
        task.setTitle(archive.getTitle());
        task.setRepository(archive.getRepository());
        task.setOrganization(archive.getOrganization());
        task.setCommitSha(archive.getCommitSha());
        task.setBranchName(archive.getBranchName());
        task.setStatus(archive.getStatus());
        task.setRiskLevel(archive.getRiskLevel());
        task.setAssessmentStatus(archive.getAssessmentStatus());
        task.setMqRetries(0);
        task.setLlmStatus(archive.getStatus());
        task.setSource(archive.getSource());
        task.setTriggerSource(archive.getTriggerSource());
        task.setCreatedAt(archive.getCreatedAt());
        task.setFinishedAt(archive.getFinishedAt());
        task.setDurationSeconds(archive.getDurationSeconds());
        task.setHumanReviewRequired(false);
        task.setHumanReviewStatus("NOT_REQUIRED");
        return task;
    }


    public ReviewTaskListItem item(ReviewTaskArchiveSummary archive) {
        return items.assembleArchived(task(archive));
    }

    public ReviewTimelineItem timeline(ReviewTaskArchiveSummary archive) {
        return new ReviewTimelineItem("Review task archived; summary restored from retention archive",
            time(archive.getArchivedAt()), "done");
    }

    private long count(Integer value) { return value == null ? 0L : value.longValue(); }
    private String time(LocalDateTime value) {
        return value == null ? null : value.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
