package com.repoguard.agent.service;

import com.repoguard.agent.dto.ManualReviewRequest;
import com.repoguard.agent.dto.ManualReviewResponse;
import com.repoguard.agent.dto.PageResponse;
import com.repoguard.agent.dto.ReviewQuery;
import com.repoguard.agent.dto.ReviewTaskDetail;
import com.repoguard.agent.dto.ReviewTaskListItem;

public interface ReviewService {

    /**
     * 根据接口层筛选条件查询评审任务列表，并转换为数据库查询条件。
     */
    PageResponse<ReviewTaskListItem> listReviews(ReviewQuery query);

    /**
     * 加载单个评审任务，以及前端详情页需要的所有只读区块。
     */
    ReviewTaskDetail getReviewDetail(Long id);

    ManualReviewResponse triggerManualReview(ManualReviewRequest request);
}
