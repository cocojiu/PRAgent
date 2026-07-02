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

export const fetchReviewStatus = (id: number) => apiRequest("fetchReviewStatus", { id });

export const fetchGithubCommentPreview = (id: number) =>
  apiRequest("fetchGithubCommentPreview", { id });

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
