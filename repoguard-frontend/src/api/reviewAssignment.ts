import { apiRequest, type ApiRequestOptions } from "@/api/contracts";
import type { ReviewMemberAssignmentRequest } from "@/types";

export const fetchReviewAssignmentOptions = (taskId: number, search?: string, options?: ApiRequestOptions) =>
  apiRequest("fetchReviewAssignmentOptions", { taskId, search }, options);
export const confirmReviewMemberAssignment = (taskId: number, input: ReviewMemberAssignmentRequest, options?: ApiRequestOptions) =>
  apiRequest("confirmReviewMemberAssignment", { taskId, input }, options);
