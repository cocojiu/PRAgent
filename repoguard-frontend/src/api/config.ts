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
  ReviewRulesResponse,
  NotificationBinding,
  NotificationBindingRequest,
  NotificationBindingStatusRequest,
  NotificationDelivery,
  NotificationEvent,
  PageResponse,
  ServiceIntegrationConfig,
  ServiceIntegrationConfigRequest,
  SystemSettings,
  SystemSettingsRequest
} from "@/types";

export const fetchGithubIntegrationConfig = () =>
  request<GithubIntegrationConfig>("/api/v1/config/integrations/github");

export const updateGithubIntegrationConfig = (payload: GithubIntegrationConfigRequest) =>
  request<GithubIntegrationConfig>("/api/v1/config/integrations/github", undefined, {
    method: "PUT",
    body: JSON.stringify(payload)
  });

export const fetchMysqlIntegrationConfig = () =>
  request<ServiceIntegrationConfig>("/api/v1/config/integrations/mysql");

export const updateMysqlIntegrationConfig = (payload: ServiceIntegrationConfigRequest) =>
  request<ServiceIntegrationConfig>("/api/v1/config/integrations/mysql", undefined, {
    method: "PUT",
    body: JSON.stringify(payload)
  });

export const fetchRabbitMqIntegrationConfig = () =>
  request<ServiceIntegrationConfig>("/api/v1/config/integrations/rabbitmq");

export const updateRabbitMqIntegrationConfig = (payload: ServiceIntegrationConfigRequest) =>
  request<ServiceIntegrationConfig>("/api/v1/config/integrations/rabbitmq", undefined, {
    method: "PUT",
    body: JSON.stringify(payload)
  });

export const fetchReviewPolicyConfig = () => request<ReviewPolicyConfig>("/api/v1/config/review-policy");

export const updateReviewPolicyConfig = (payload: ReviewPolicyConfigRequest) =>
  request<ReviewPolicyConfig>("/api/v1/config/review-policy", undefined, {
    method: "PUT",
    body: JSON.stringify(payload)
  });

export const fetchSystemSettings = () => request<SystemSettings>("/api/v1/config/system-settings");

export const updateSystemSettings = (payload: SystemSettingsRequest) =>
  request<SystemSettings>("/api/v1/config/system-settings", undefined, {
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

export const testGithubIntegrationConnection = (payload?: GithubIntegrationConfigRequest) =>
  request<ConnectionTestResult>("/api/v1/config/integrations/github/test", undefined, {
    method: "POST",
    body: payload ? JSON.stringify(payload) : undefined
  });

export const testMysqlConnection = (payload?: ServiceIntegrationConfigRequest) =>
  request<ConnectionTestResult>("/api/v1/config/integrations/mysql/test", undefined, {
    method: "POST",
    body: payload ? JSON.stringify(payload) : undefined
  });

export const testRabbitMqConnection = (payload?: ServiceIntegrationConfigRequest) =>
  request<ConnectionTestResult>("/api/v1/config/integrations/rabbitmq/test", undefined, {
    method: "POST",
    body: payload ? JSON.stringify(payload) : undefined
  });

export const testReviewPolicyConnection = (payload?: ReviewPolicyConfigRequest) =>
  request<ConnectionTestResult>("/api/v1/config/review-policy/test", undefined, {
    method: "POST",
    body: payload ? JSON.stringify(payload) : undefined
  });

export const fetchNotificationBindings = () =>
  request<PageResponse<NotificationBinding>>("/api/v1/config/notification-bindings?page=1&pageSize=100");

export const createNotificationBinding = (payload: NotificationBindingRequest) =>
  request<NotificationBinding>("/api/v1/config/notification-bindings", undefined, {
    method: "POST",
    body: JSON.stringify(payload)
  });

export const updateNotificationBinding = (id: number, payload: NotificationBindingRequest) =>
  request<NotificationBinding>(`/api/v1/config/notification-bindings/${id}`, undefined, {
    method: "PUT",
    body: JSON.stringify(payload)
  });

export const updateNotificationBindingStatus = (id: number, payload: NotificationBindingStatusRequest) =>
  request<NotificationBinding>(`/api/v1/config/notification-bindings/${id}/status`, undefined, {
    method: "PUT",
    body: JSON.stringify(payload)
  });

export const deleteNotificationBinding = (id: number) =>
  request<void>(`/api/v1/config/notification-bindings/${id}`, undefined, {
    method: "DELETE"
  });

export const testNotificationBinding = (id: number) =>
  request<ConnectionTestResult>(`/api/v1/config/notification-bindings/${id}/test`, undefined, {
    method: "POST"
  });

const buildNotificationQuery = (params: { page?: number; pageSize?: number; status?: string; taskId?: number }) => {
  const search = new URLSearchParams({
    page: String(params.page ?? 1),
    pageSize: String(params.pageSize ?? 20)
  });
  if (params.status) {
    search.set("status", params.status);
  }
  if (params.taskId) {
    search.set("taskId", String(params.taskId));
  }
  return search.toString();
};

export const fetchNotificationEvents = (params: { page?: number; pageSize?: number; status?: string; taskId?: number } = {}) =>
  request<PageResponse<NotificationEvent>>(`/api/v1/notification-events?${buildNotificationQuery(params)}`);

export const retryNotificationEvent = (id: number) =>
  request<NotificationEvent>(`/api/v1/notification-events/${id}/retry`, undefined, {
    method: "POST"
  });

export const fetchNotificationDeliveries = (params: { page?: number; pageSize?: number; status?: string; taskId?: number } = {}) =>
  request<PageResponse<NotificationDelivery>>(`/api/v1/notification-deliveries?${buildNotificationQuery(params)}`);
