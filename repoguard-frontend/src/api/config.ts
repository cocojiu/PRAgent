import { request } from "@/api/client";
import type {
  GithubIntegrationConfig,
  GithubIntegrationConfigRequest,
  ConnectionTestResult,
  ReviewPolicyConfig,
  ReviewPolicyConfigRequest,
  ReviewRuleConfig,
  ReviewRuleConfigRequest,
  ReviewRuleStatusRequest,
  ReviewRulesResponse
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

export const fetchReviewRules = () => request<ReviewRulesResponse>("/api/v1/config/review-rules");

export const createReviewRule = (payload: ReviewRuleConfigRequest) =>
  request<ReviewRuleConfig>("/api/v1/config/review-rules", undefined, {
    method: "POST",
    body: JSON.stringify(payload)
  });

export const updateReviewRule = (id: string, payload: ReviewRuleConfigRequest) =>
  request<ReviewRuleConfig>(`/api/v1/config/review-rules/${encodeURIComponent(id)}`, undefined, {
    method: "PUT",
    body: JSON.stringify(payload)
  });

export const updateReviewRuleStatus = (id: string, payload: ReviewRuleStatusRequest) =>
  request<ReviewRuleConfig>(`/api/v1/config/review-rules/${encodeURIComponent(id)}/status`, undefined, {
    method: "PUT",
    body: JSON.stringify(payload)
  });

export const testGithubIntegrationConnection = () =>
  request<ConnectionTestResult>("/api/v1/config/integrations/github/test", undefined, { method: "POST" });

export const testMysqlConnection = () =>
  request<ConnectionTestResult>("/api/v1/config/integrations/mysql/test", undefined, { method: "POST" });

export const testRabbitMqConnection = () =>
  request<ConnectionTestResult>("/api/v1/config/integrations/rabbitmq/test", undefined, { method: "POST" });

export const testReviewPolicyConnection = () =>
  request<ConnectionTestResult>("/api/v1/config/review-policy/test", undefined, { method: "POST" });
