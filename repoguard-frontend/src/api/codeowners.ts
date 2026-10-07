import { apiRequest, type ApiRequestOptions } from "@/api/contracts";
import type { CodeownersAcceptanceRequest } from "@/types";

export const fetchCodeownersRecommendations = (taskId: number, options?: ApiRequestOptions) =>
  apiRequest("fetchCodeownersRecommendations", { taskId }, options);

export const acceptCodeownersRecommendation = (taskId: number, input: CodeownersAcceptanceRequest,
  options?: ApiRequestOptions) => apiRequest("acceptCodeownersRecommendation", { taskId, input }, options);
