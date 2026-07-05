import { apiRequest } from "@/api/contracts";
import type {
  FindingFeedbackRequest,
  HumanReviewRequest,
  ManualReviewRequest,
  ReviewQuery
} from "@/types";

/**
 * 查询评审任务列表，参数与后端只读 API 保持一致。
 */
export const fetchReviews = (query: ReviewQuery) =>
  apiRequest("fetchReviews", query);

/**
 * 查询单个评审任务详情。
 */
export const fetchReviewDetail = (id: number) => apiRequest("fetchReviewDetail", { id });

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

export const fetchReviewRepositories = () =>
  apiRequest("fetchReviewRepositories", undefined);

export const fetchReviewStatus = (id: number) => apiRequest("fetchReviewStatus", { id });

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
