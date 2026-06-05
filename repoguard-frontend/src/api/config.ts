import { request } from "@/api/client";
import type {
  GithubIntegrationConfig,
  GithubIntegrationConfigRequest,
  ReviewPolicyConfig,
  ReviewPolicyConfigRequest
} from "@/types";

export const fetchGithubIntegrationConfig = () =>
  request<GithubIntegrationConfig>("/api/v1/config/integrations/github");

export const updateGithubIntegrationConfig = (payload: GithubIntegrationConfigRequest) =>
  request<GithubIntegrationConfig>("/api/v1/config/integrations/github", undefined, {
    method: "PUT",
    body: JSON.stringify(payload)
  });

export const fetchReviewPolicyConfig = () => request<ReviewPolicyConfig>("/api/v1/config/review-policy");

export const updateReviewPolicyConfig = (payload: ReviewPolicyConfigRequest) =>
  request<ReviewPolicyConfig>("/api/v1/config/review-policy", undefined, {
    method: "PUT",
    body: JSON.stringify(payload)
  });
