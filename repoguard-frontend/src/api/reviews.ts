import { apiRequest, type ApiRequestOptions } from "@/api/contracts";
import type {
  FindingFeedbackRequest,
  HumanReviewRequest,
  ManualReviewRequest,
  ReviewQuery,
  ReviewTaskListSummaryQuery
} from "@/types";

/**
 * 查询评审任务列表，参数与后端只读 API 保持一致。
 */
export const fetchReviews = (query: ReviewQuery) =>
  apiRequest("fetchReviews", query);

/**
 * 查询当前筛选条件下的任务聚合指标，筛选口径与列表接口同源。
 */
export const fetchReviewListSummary = (query: ReviewTaskListSummaryQuery) =>
  apiRequest("fetchReviewListSummary", query);

/**
 * 查询单个评审任务首屏 summary；findings/files/missing-tests/timeline 通过分页接口加载。
 */
export const fetchReviewDetail = (id: number, options?: ApiRequestOptions) =>
  apiRequest("fetchReviewDetail", { id }, options);

export const fetchReviewFindings = (
  id: number,
  params?: { page?: number; pageSize?: number; severity?: string; category?: string; feedbackStatus?: string }
) => apiRequest("fetchReviewFindings", { id, ...params });

export const fetchReviewChangedFiles = (
  id: number,
  params?: { page?: number; pageSize?: number; hasFinding?: boolean }
) => apiRequest("fetchReviewChangedFiles", { id, ...params });

export const fetchReviewMissingTests = (
  id: number,
  params?: { page?: number; pageSize?: number }
) => apiRequest("fetchReviewMissingTests", { id, ...params });

export const fetchReviewTimeline = (
  id: number,
  params?: { limit?: number }
) => apiRequest("fetchReviewTimeline", { id, ...params });

export const fetchReviewRepositories = () =>
  apiRequest("fetchReviewRepositories", undefined);

export const fetchReviewStatus = (id: number, options?: ApiRequestOptions) =>
  apiRequest("fetchReviewStatus", { id }, options);

export const fetchGithubCommentPreview = (
  id: number,
  params?: { page?: number; pageSize?: number; commentableOnly?: boolean }
) => apiRequest("fetchGithubCommentPreview", { id, ...params });

export const fetchGithubCommentPublicationHistory = (
  id: number,
  params?: { page?: number; pageSize?: number; status?: string }
) => apiRequest("fetchGithubCommentPublicationHistory", { id, ...params });

export const publishGithubComments = (id: number) =>
  apiRequest("publishGithubComments", { id });

export const submitHumanReview = (id: number, payload: HumanReviewRequest) =>
  apiRequest("submitHumanReview", { id, payload });

export const updateFindingFeedback = (id: number, findingId: number, payload: FindingFeedbackRequest) =>
  apiRequest("updateFindingFeedback", { id, findingId, payload });

export const retryReview = (id: number) =>
  apiRequest("retryReview", { id });

export const fetchGithubPullRequestOptions = () =>
  apiRequest("fetchGithubPullRequestOptions", undefined);

export const triggerManualReview = (payload: ManualReviewRequest) =>
  apiRequest("triggerManualReview", payload);
