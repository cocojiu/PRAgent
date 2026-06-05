import { request } from "@/api/client";
import type { PageResponse, ReviewQuery, ReviewTask, ReviewTaskDetail } from "@/types";

/**
 * 查询评审任务列表，参数与后端只读 API 保持一致。
 */
export const fetchReviews = (query: ReviewQuery) =>
  request<PageResponse<ReviewTask>>("/api/v1/reviews", {
    page: query.page,
    pageSize: query.pageSize,
    repository: query.repository,
    status: query.status,
    riskLevel: query.riskLevel,
    keyword: query.keyword
  });

/**
 * 查询单个评审任务详情。
 */
export const fetchReviewDetail = (id: number) => request<ReviewTaskDetail>(`/api/v1/reviews/${id}`);
