import { apiRequest } from "@/api/contracts";
import type { ApiRequestOptions } from "@/api/contracts";
import type {
  DataRetentionCleanupRequest,
  GithubIntegrationConfigRequest,
  GithubChecksPolicyRequest,
  GithubChecksPreviewRequest,
  LlmEvaluationRequest,
  LlmEvaluationReportLifecycleRequest,
  LlmModelReleaseDriftRepairRequest,
  LlmModelReleaseRequest,
  LlmModelRollbackRequest,
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

export const fetchGithubChecksSetup = (organization: string, repository: string) =>
  apiRequest("fetchGithubChecksSetup", { organization, repository });

export const previewGithubChecks = (payload: GithubChecksPreviewRequest) =>
  apiRequest("previewGithubChecks", payload);

export const updateGithubChecksPolicy = (payload: GithubChecksPolicyRequest) =>
  apiRequest("updateGithubChecksPolicy", payload);

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

export const fetchSecretReEncryptionJob = (jobId: number) =>
  apiRequest("fetchSecretReEncryptionJob", { jobId });

export const fetchSecretReEncryptionJobs = (page = 1, pageSize = 20) =>
  apiRequest("fetchSecretReEncryptionJobs", { page, pageSize });

export const fetchSecretReEncryptionJobItems = (
  jobId: number,
  page = 1,
  pageSize = 50
) => apiRequest("fetchSecretReEncryptionJobItems", { jobId, page, pageSize });

export const pauseSecretReEncryptionJob = (jobId: number) =>
  apiRequest("pauseSecretReEncryptionJob", { jobId });

export const resumeSecretReEncryptionJob = (jobId: number) =>
  apiRequest("resumeSecretReEncryptionJob", { jobId });

export const fetchReviewRules = () => apiRequest("fetchReviewRules", undefined);

export const fetchReviewCalibrationQueue = (
  ruleId: string,
  options: { limit?: number; includeIgnored?: boolean } = {}
) => apiRequest("fetchReviewCalibrationQueue", { ruleId, ...options });

export const fetchLlmModelReleaseCenter = (trendDays = 30) =>
  apiRequest("fetchLlmModelReleaseCenter", { trendDays });

export const fetchLlmModelReleaseRuntimeMetrics = (
  options: { releaseKey?: string; days?: number; limit?: number } = {}
) => apiRequest("fetchLlmModelReleaseRuntimeMetrics", options);

export const fetchLlmModelReleaseDrift = () => apiRequest("fetchLlmModelReleaseDrift", undefined);

export const repairLlmModelReleaseDrift = (payload: LlmModelReleaseDriftRepairRequest) =>
  apiRequest("repairLlmModelReleaseDrift", payload);

export type LlmModelReleaseAuditQuery = {
  releaseId?: number;
  releaseKey?: string;
  operator?: string;
  action?: string;
  from?: string;
  to?: string;
  page?: number;
  pageSize?: number;
};

export const fetchLlmModelReleaseAudits = (options: LlmModelReleaseAuditQuery = {}) =>
  apiRequest("fetchLlmModelReleaseAudits", options);

export const verifyLlmModelReleaseAudit = (auditId: number) =>
  apiRequest("verifyLlmModelReleaseAudit", { auditId });

export const exportLlmModelReleaseAudits = (
  options: Omit<LlmModelReleaseAuditQuery, "page" | "pageSize"> & { format?: "json" | "csv" } = {}
) => apiRequest("exportLlmModelReleaseAudits", options);

export const registerLlmModelShadowRelease = (payload: LlmModelReleaseRequest) =>
  apiRequest("registerLlmModelShadowRelease", payload);

export const promoteLlmModelRelease = (payload: LlmModelReleaseRequest) =>
  apiRequest("promoteLlmModelRelease", payload);

export const createLlmEvaluationReport = (payload: LlmEvaluationRequest) =>
  apiRequest("createLlmEvaluationReport", payload);

export const fetchLlmEvaluationReports = (limit = 30) =>
  apiRequest("fetchLlmEvaluationReports", { limit });

export const fetchLlmEvaluationReport = (reportId: number) =>
  apiRequest("fetchLlmEvaluationReport", { reportId });

export const compareLlmEvaluationReports = (reportId: number, candidateReportId: number) =>
  apiRequest("compareLlmEvaluationReports", { reportId, candidateReportId });

export const exportLlmEvaluationReport = (reportId: number, format: "json" | "html" = "json") =>
  apiRequest("exportLlmEvaluationReport", { reportId, format });

export const transitionLlmEvaluationReportLifecycle = (
  reportId: number,
  payload: LlmEvaluationReportLifecycleRequest
) => apiRequest("transitionLlmEvaluationReportLifecycle", { reportId, payload });

export const rollbackLlmModelRelease = (releaseId: number, payload: LlmModelRollbackRequest) =>
  apiRequest("rollbackLlmModelRelease", { releaseId, payload });

export const createReviewRule = (payload: ReviewRuleConfigRequest) =>
  apiRequest("createReviewRule", payload);

export const updateReviewRule = (
  id: string,
  expectedPolicyVersion: number,
  payload: ReviewRuleConfigRequest
) => apiRequest("updateReviewRule", { id, expectedPolicyVersion, payload });

export const updateReviewRuleStatus = (id: string, payload: ReviewRuleStatusRequest) =>
  apiRequest("updateReviewRuleStatus", { id, payload });

export const fetchReviewRuleVersions = (
  id: string,
  options: { cursor?: string; pageSize?: number } = {},
  requestOptions: ApiRequestOptions = {}
) => apiRequest("fetchReviewRuleVersions", { id, ...options }, requestOptions);

export const rollbackReviewRule = (
  id: string,
  policyVersion: number,
  expectedPolicyVersion: number
) => apiRequest("rollbackReviewRule", { id, policyVersion, expectedPolicyVersion });

export const fetchReviewStrategy = () => apiRequest("fetchReviewStrategy", undefined);

export const fetchReviewStrategyVersions = (
  options: { cursor?: string; pageSize?: number } = {},
  requestOptions: ApiRequestOptions = {}
) => apiRequest("fetchReviewStrategyVersions", options, requestOptions);

export const updateReviewStrategyEnforcement = (payload: ReviewEnforcementModeRequest) =>
  apiRequest("updateReviewStrategyEnforcement", payload);

export const rollbackReviewStrategy = (snapshotId: number, expectedSnapshotId: number) =>
  apiRequest("rollbackReviewStrategy", { snapshotId, expectedSnapshotId });

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
