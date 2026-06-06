import { request } from "@/api/client";
import type {
  GithubIntegrationConfig,
  GithubIntegrationConfigRequest,
  ConnectionTestResult,
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

export const testGithubIntegrationConnection = () =>
  request<ConnectionTestResult>("/api/v1/config/integrations/github/test", undefined, { method: "POST" });

export const testMysqlConnection = () =>
  request<ConnectionTestResult>("/api/v1/config/integrations/mysql/test", undefined, { method: "POST" });

export const testRabbitMqConnection = () =>
  request<ConnectionTestResult>("/api/v1/config/integrations/rabbitmq/test", undefined, { method: "POST" });

export const testReviewPolicyConnection = () =>
  request<ConnectionTestResult>("/api/v1/config/review-policy/test", undefined, { method: "POST" });
