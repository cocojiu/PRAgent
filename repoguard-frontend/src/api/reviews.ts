import { request } from "@/api/client";
import type {
  ManualReviewRequest,
  ManualReviewResponse,
  PageResponse,
  ReviewQuery,
  ReviewTask,
  ReviewTaskDetail,
  GithubCommentPreview,
  GithubCommentPublicationHistory,
  GithubCommentPublish,
  GithubPullRequestOptions
} from "@/types";

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
    source: query.source,
    triggerSource: query.triggerSource,
    keyword: query.keyword
  });

/**
 * 查询单个评审任务详情。
 */
export const fetchReviewDetail = (id: number) => request<ReviewTaskDetail>(`/api/v1/reviews/${id}`);

export const fetchGithubCommentPreview = (id: number) =>
  request<GithubCommentPreview>(`/api/v1/reviews/${id}/github-comments/preview`);

export const fetchGithubCommentPublicationHistory = (id: number) =>
  request<GithubCommentPublicationHistory>(`/api/v1/reviews/${id}/github-comments/publications`);

export const publishGithubComments = (id: number) =>
  request<GithubCommentPublish>(`/api/v1/reviews/${id}/github-comments`, undefined, {
    method: "POST"
  });

export const fetchGithubPullRequestOptions = () =>
  request<GithubPullRequestOptions>("/api/v1/reviews/github/pull-requests");

export const triggerManualReview = (payload: ManualReviewRequest) =>
  request<ManualReviewResponse>("/api/v1/reviews/manual", undefined, {
    method: "POST",
    body: JSON.stringify(payload)
  });
