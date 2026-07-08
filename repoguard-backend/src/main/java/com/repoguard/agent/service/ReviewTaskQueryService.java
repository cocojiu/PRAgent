package com.repoguard.agent.service;

import com.repoguard.agent.dto.PageResponse;
import com.repoguard.agent.dto.ReviewQuery;
import com.repoguard.agent.dto.ChangedFileDto;
import com.repoguard.agent.dto.MissingTestDto;
import com.repoguard.agent.dto.ReviewFindingDto;
import com.repoguard.agent.dto.ReviewTaskListItem;
import com.repoguard.agent.dto.ReviewTaskSummary;
import com.repoguard.agent.dto.ReviewTaskStatusResponse;
import com.repoguard.agent.dto.ReviewTimelineItem;
import java.util.List;

public interface ReviewTaskQueryService {

    PageResponse<ReviewTaskListItem> listReviews(ReviewQuery query);

    List<String> listRepositories();

    ReviewTaskSummary getReviewDetail(Long id);

    PageResponse<ReviewFindingDto> listReviewFindings(
        Long id,
        int page,
        int pageSize,
        String severity,
        String category,
        String feedbackStatus
    );

    PageResponse<ChangedFileDto> listChangedFiles(Long id, int page, int pageSize, Boolean hasFinding);

    PageResponse<MissingTestDto> listMissingTests(Long id, int page, int pageSize);

    List<ReviewTimelineItem> listReviewTimeline(Long id, int limit);

    ReviewTaskStatusResponse getReviewStatus(Long id);
}
