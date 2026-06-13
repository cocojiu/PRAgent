import { request } from "@/api/client";
import type {
  HumanReviewRequest,
  HumanReviewResponse,
  ManualReviewRequest,
  ManualReviewResponse,
  PageResponse,
  ReviewQuery,
  ReviewRetryResponse,
  ReviewTask,
  ReviewTaskDetail,
  ReviewTaskStatus,
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

export const fetchReviewStatus = (id: number) => request<ReviewTaskStatus>(`/api/v1/reviews/${id}/status`);

export const fetchGithubCommentPreview = (id: number) =>
  request<GithubCommentPreview>(`/api/v1/reviews/${id}/github-comments/preview`);

export const fetchGithubCommentPublicationHistory = (
  id: number,
  params?: { page?: number; pageSize?: number; status?: string }
) => {
  const searchParams = new URLSearchParams();
  if (params?.page) {
    searchParams.set("page", String(params.page));
  }
  if (params?.pageSize) {
    searchParams.set("pageSize", String(params.pageSize));
  }
  if (params?.status) {
    searchParams.set("status", params.status);
  }
  const query = searchParams.toString();
  return request<GithubCommentPublicationHistory>(
    `/api/v1/reviews/${id}/github-comments/publications${query ? `?${query}` : ""}`
  );
};

export const publishGithubComments = (id: number) =>
  request<GithubCommentPublish>(`/api/v1/reviews/${id}/github-comments`, undefined, {
    method: "POST"
  });

export const submitHumanReview = (id: number, payload: HumanReviewRequest) =>
  request<HumanReviewResponse>(`/api/v1/reviews/${id}/human-review`, undefined, {
    method: "POST",
    body: JSON.stringify(payload)
  });

export const retryReview = (id: number) =>
  request<ReviewRetryResponse>(`/api/v1/reviews/${id}/retry`, undefined, {
    method: "POST"
  });

export const fetchGithubPullRequestOptions = () =>
  request<GithubPullRequestOptions>("/api/v1/reviews/github/pull-requests");

export const triggerManualReview = (payload: ManualReviewRequest) =>
  request<ManualReviewResponse>("/api/v1/reviews/manual", undefined, {
    method: "POST",
    body: JSON.stringify(payload)
  });
