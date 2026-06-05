package com.repoguard.agent.controller;

import com.repoguard.agent.common.ApiResponse;
import com.repoguard.agent.dto.PageResponse;
import com.repoguard.agent.dto.ReviewQuery;
import com.repoguard.agent.dto.ReviewTaskDetail;
import com.repoguard.agent.dto.ReviewTaskListItem;
import com.repoguard.agent.service.ReviewService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/reviews")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /**
     * 查询评审任务列表。
     *
     * <p>支持仓库、状态、风险等级和关键字筛选，并在接口边界限制分页大小。
     */
    @GetMapping
    public ApiResponse<PageResponse<ReviewTaskListItem>> listReviews(
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
        @RequestParam(required = false) String repository,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String riskLevel,
        @RequestParam(required = false) String keyword
    ) {
        ReviewQuery query = new ReviewQuery(page, pageSize, repository, status, riskLevel, keyword);
        return ApiResponse.ok(reviewService.listReviews(query));
    }

    /**
     * 返回 PR 评审详情页需要的完整只读数据。
     */
    @GetMapping("/{id}")
    public ApiResponse<ReviewTaskDetail> getReviewDetail(@PathVariable @Min(1) Long id) {
        return ApiResponse.ok(reviewService.getReviewDetail(id));
    }
}
