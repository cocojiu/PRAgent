package com.repoguard.agent.service;

import com.repoguard.agent.dto.PageResponse;
import com.repoguard.agent.dto.ReviewQuery;
import com.repoguard.agent.dto.ReviewTaskDetail;
import com.repoguard.agent.dto.ReviewTaskListItem;
import com.repoguard.agent.dto.ReviewTaskStatusResponse;

public interface ReviewTaskQueryService {

    PageResponse<ReviewTaskListItem> listReviews(ReviewQuery query);

    ReviewTaskDetail getReviewDetail(Long id);

    ReviewTaskStatusResponse getReviewStatus(Long id);
}
