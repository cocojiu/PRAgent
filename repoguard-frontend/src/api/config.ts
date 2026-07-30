import { apiRequest } from "@/api/contracts";
import type {
  DataRetentionCleanupRequest,
  GithubIntegrationConfigRequest,
  ReviewPolicyConfigRequest,
  ReviewEnforcementModeRequest,
  ReviewRuleConfigRequest,
  ReviewRuleStatusRequest,
  NotificationBindingRequest,
  NotificationBindingStatusRequest,
  SecretReEncryptionRequest,
  ServiceIntegrationConfigRequest,
  SystemSettingsRequest
} from "@/types";

export type DataRetentionCleanupAuditQuery = {
  page?: number;
  pageSize?: number;
  mode?: string;
  status?: string;
  backupReference?: string;
};

export const fetchGithubIntegrationConfig = () =>
  apiRequest("fetchGithubIntegrationConfig", undefined);

export const updateGithubIntegrationConfig = (payload: GithubIntegrationConfigRequest) =>
  apiRequest("updateGithubIntegrationConfig", payload);

export const fetchMysqlIntegrationConfig = () =>
  apiRequest("fetchMysqlIntegrationConfig", undefined);

export const updateMysqlIntegrationConfig = (payload: ServiceIntegrationConfigRequest) =>
  apiRequest("updateMysqlIntegrationConfig", payload);

export const fetchRabbitMqIntegrationConfig = () =>
  apiRequest("fetchRabbitMqIntegrationConfig", undefined);

export const updateRabbitMqIntegrationConfig = (payload: ServiceIntegrationConfigRequest) =>
  apiRequest("updateRabbitMqIntegrationConfig", payload);

export const fetchReviewPolicyConfig = () => apiRequest("fetchReviewPolicyConfig", undefined);

export const updateReviewPolicyConfig = (payload: ReviewPolicyConfigRequest) =>
  apiRequest("updateReviewPolicyConfig", payload);

export const fetchSystemSettings = () => apiRequest("fetchSystemSettings", undefined);

export const updateSystemSettings = (payload: SystemSettingsRequest) =>
  apiRequest("updateSystemSettings", payload);

export const fetchCacheStats = () => apiRequest("fetchCacheStats", undefined);

export const cleanupDataRetention = (payload?: DataRetentionCleanupRequest) =>
  apiRequest("cleanupDataRetention", payload);

export const fetchDataRetentionCleanupAudits = (
  params: DataRetentionCleanupAuditQuery = {}
) => apiRequest("fetchDataRetentionCleanupAudits", params);

export const reEncryptSecrets = (payload: SecretReEncryptionRequest) =>
  apiRequest("reEncryptSecrets", payload);

export const fetchReviewRules = () => apiRequest("fetchReviewRules", undefined);

export const createReviewRule = (payload: ReviewRuleConfigRequest) =>
  apiRequest("createReviewRule", payload);

export const updateReviewRule = (id: string, payload: ReviewRuleConfigRequest) =>
  apiRequest("updateReviewRule", { id, payload });

export const updateReviewRuleStatus = (id: string, payload: ReviewRuleStatusRequest) =>
  apiRequest("updateReviewRuleStatus", { id, payload });

export const fetchReviewRuleVersions = (id: string) =>
  apiRequest("fetchReviewRuleVersions", { id });

export const rollbackReviewRule = (id: string, policyVersion: number) =>
  apiRequest("rollbackReviewRule", { id, policyVersion });

export const fetchReviewStrategy = () => apiRequest("fetchReviewStrategy", undefined);

export const fetchReviewStrategyVersions = () =>
  apiRequest("fetchReviewStrategyVersions", undefined);

export const updateReviewStrategyEnforcement = (payload: ReviewEnforcementModeRequest) =>
  apiRequest("updateReviewStrategyEnforcement", payload);

export const rollbackReviewStrategy = (snapshotId: number) =>
  apiRequest("rollbackReviewStrategy", { snapshotId });

export const testGithubIntegrationConnection = (payload?: GithubIntegrationConfigRequest) =>
  apiRequest("testGithubIntegrationConnection", payload);

export const testMysqlConnection = (payload?: ServiceIntegrationConfigRequest) =>
  apiRequest("testMysqlConnection", payload);

export const testRabbitMqConnection = (payload?: ServiceIntegrationConfigRequest) =>
  apiRequest("testRabbitMqConnection", payload);

export const testReviewPolicyConnection = (payload?: ReviewPolicyConfigRequest) =>
  apiRequest("testReviewPolicyConnection", payload);

export type NotificationBindingPageQuery = {
  page?: number;
  pageSize?: number;
  organization?: string;
  repository?: string;
  provider?: string;
};

export const fetchNotificationBindings = (query: NotificationBindingPageQuery = {}) =>
  apiRequest("fetchNotificationBindings", query);

export const createNotificationBinding = (payload: NotificationBindingRequest) =>
  apiRequest("createNotificationBinding", payload);

export const updateNotificationBinding = (id: number, payload: NotificationBindingRequest) =>
  apiRequest("updateNotificationBinding", { id, payload });

export const updateNotificationBindingStatus = (id: number, payload: NotificationBindingStatusRequest) =>
  apiRequest("updateNotificationBindingStatus", { id, payload });

export const deleteNotificationBinding = (id: number) =>
  apiRequest("deleteNotificationBinding", { id });

export const testNotificationBinding = (id: number) =>
  apiRequest("testNotificationBinding", { id });

export const fetchNotificationEvents = (params: { page?: number; pageSize?: number; status?: string; taskId?: number } = {}) =>
  apiRequest("fetchNotificationEvents", params);

export const retryNotificationEvent = (id: number) =>
  apiRequest("retryNotificationEvent", { id });

export const fetchNotificationDeliveries = (params: { page?: number; pageSize?: number; status?: string; taskId?: number } = {}) =>
  apiRequest("fetchNotificationDeliveries", params);
