package com.repoguard.agent.service;

import com.repoguard.agent.dto.PageResponse;
import com.repoguard.agent.dto.ReviewQuery;
import com.repoguard.agent.dto.ChangedFileDto;
import com.repoguard.agent.dto.MissingTestDto;
import com.repoguard.agent.dto.ReviewFindingDto;
import com.repoguard.agent.dto.ReviewTaskDetail;
import com.repoguard.agent.dto.ReviewTaskListItem;
import com.repoguard.agent.dto.ReviewTaskStatusResponse;

public interface ReviewTaskQueryService {

    PageResponse<ReviewTaskListItem> listReviews(ReviewQuery query);

    ReviewTaskDetail getReviewDetail(Long id);

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

    ReviewTaskStatusResponse getReviewStatus(Long id);
}
