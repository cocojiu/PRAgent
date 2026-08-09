import { requestWithMeta } from "@/api/client";
import type { ClientRequestInit } from "@/api/client";
import type {
  ApiEndpoint,
  ApiOperation
} from "@/api/endpoint";
import { generatedEndpoint } from "@/api/generatedEndpoint";
import { observeFrontendApiRequest } from "@/observability/frontendPerformanceBuffer";
import type { FrontendPerformanceReport } from "@/observability/frontendPerformanceBuffer";
import type { AuthResponse, CurrentUser, LoginRequest, PasswordChangeRequest, RefreshTokenResetRequest, RegisterRequest } from "@/api/auth";
import type { ManagedUser, UserCreateRequest, UserOperationAudit, UserRole, UserStatus } from "@/api/users";
import {
  isAuthResponse,
  isCurrentUser,
  isGithubIntegrationConfig,
  isReviewPolicyConfig,
  isReviewTaskSummary,
  isSecretReEncryptionItemPage,
  isSecretReEncryptionJob,
  isSecretReEncryptionJobPage,
  isServiceIntegrationConfig,
  validateApiResponse,
} from "@/api/responseValidation";
import type {
  ConnectionTestResult,
  ChartSlice,
  DashboardLlmQuality,
  DashboardMetric,
  DashboardOverview,
  DashboardRules,
  DataRetentionCleanupAudit,
  DataRetentionCleanupRequest,
  DataRetentionCleanupResponse,
  FindingFeedbackRequest,
  FindingFeedbackResponse,
  CacheStats,
  ChangedFile,
  GithubCommentPreview,
  GithubCommentPublicationHistory,
  GithubCommentPublish,
  GithubIntegrationConfig,
  GithubIntegrationConfigRequest,
  GithubPullRequestOptions,
  HighRiskReview,
  HumanReviewRequest,
  HumanReviewResponse,
  ManualReviewRequest,
  ManualReviewResponse,
  MessageQueueHealth,
  MessageQueueRequeueResponse,
  NotificationBinding,
  NotificationBindingRequest,
  NotificationBindingStatusRequest,
  NotificationCenter,
  NotificationDelivery,
  NotificationEvent,
  PageResponse,
  MissingTest,
  ReviewPolicyConfig,
  ReviewPolicyConfigRequest,
  ReviewQuery,
  ReviewFinding,
  ReviewCalibrationQueue,
  ReviewRetryResponse,
  ReviewRuleConfig,
  ReviewRuleConfigRequest,
  ReviewRulePolicyVersion,
  ReviewRulesResponse,
  ReviewRuleStatusRequest,
  ReviewStrategyPolicy,
  ReviewEnforcementModeRequest,
  SecretReEncryptionJob,
  SecretReEncryptionItem,
  SecretReEncryptionRequest,
  ReviewTrendPoint,
  ReviewTask,
  ReviewTaskListSummary,
  ReviewTaskListSummaryQuery,
  ReviewTaskSummary,
  ReviewTaskStatus,
  ServiceIntegrationConfig,
  ServiceIntegrationConfigRequest,
  SystemHealthItem,
  SystemSettings,
  SystemSettingsRequest,
  TimelineItem
} from "@/types";
import { RequestError } from "@/utils/errors";

export type ApiRequestOptions = {
  signal?: AbortSignal;
  keepalive?: boolean;
  timeoutMs?: number;
};

type NotificationPageInput = {
  page?: number;
  pageSize?: number;
  status?: string;
  taskId?: number;
};

type NotificationBindingPageInput = {
  page?: number;
  pageSize?: number;
  organization?: string;
  repository?: string;
  provider?: string;
};

type ReviewDetailPageInput = {
  id: number;
  page?: number;
  pageSize?: number;
};

type ReviewFindingsPageInput = ReviewDetailPageInput & {
  severity?: string;
  category?: string;
  feedbackStatus?: string;
};

type ReviewChangedFilesPageInput = ReviewDetailPageInput & {
  hasFinding?: boolean;
};

type GithubCommentPreviewInput = ReviewDetailPageInput & {
  commentableOnly?: boolean;
};

type UserRoleInput = {
  id: number;
  role: UserRole;
};

type UserStatusInput = {
  id: number;
  status: UserStatus;
};

type UserPageInput = {
  page?: number;
  pageSize?: number;
  role?: UserRole | "";
  status?: UserStatus | "";
  keyword?: string;
};

type DataRetentionCleanupAuditInput = {
  page?: number;
  pageSize?: number;
  mode?: string;
  status?: string;
  backupReference?: string;
};

export type ApiContract = {
  login: ApiOperation<LoginRequest, AuthResponse>;
  register: ApiOperation<RegisterRequest, AuthResponse>;
  getCurrentUser: ApiOperation<undefined, CurrentUser>;
  changePassword: ApiOperation<PasswordChangeRequest, void>;
  resetRefreshToken: ApiOperation<RefreshTokenResetRequest, AuthResponse>;
  logout: ApiOperation<undefined, void>;
  fetchDashboardOverview: ApiOperation<{ llmTrendDays: number }, DashboardOverview>;
  fetchDashboardSummary: ApiOperation<undefined, DashboardMetric[]>;
  fetchDashboardReviewTrend: ApiOperation<undefined, ReviewTrendPoint[]>;
  fetchDashboardRiskDistribution: ApiOperation<undefined, Required<ChartSlice>[]>;
  fetchDashboardRules: ApiOperation<undefined, DashboardRules>;
  fetchDashboardHighRiskReviews: ApiOperation<undefined, HighRiskReview[]>;
  fetchDashboardLlmQuality: ApiOperation<{ llmTrendDays: number }, DashboardLlmQuality>;
  fetchSystemHealthSummary: ApiOperation<undefined, SystemHealthItem[]>;
  fetchCacheStats: ApiOperation<undefined, CacheStats>;
  cleanupDataRetention: ApiOperation<DataRetentionCleanupRequest | undefined, DataRetentionCleanupResponse>;
  fetchDataRetentionCleanupAudits: ApiOperation<
    DataRetentionCleanupAuditInput,
    PageResponse<DataRetentionCleanupAudit>
  >;
  fetchReviews: ApiOperation<ReviewQuery, PageResponse<ReviewTask>>;
  fetchReviewListSummary: ApiOperation<ReviewTaskListSummaryQuery, ReviewTaskListSummary>;
  fetchReviewDetail: ApiOperation<{ id: number }, ReviewTaskSummary>;
  fetchReviewFindings: ApiOperation<ReviewFindingsPageInput, PageResponse<ReviewFinding>>;
  fetchReviewChangedFiles: ApiOperation<ReviewChangedFilesPageInput, PageResponse<ChangedFile>>;
  fetchReviewMissingTests: ApiOperation<ReviewDetailPageInput, PageResponse<MissingTest>>;
  fetchReviewTimeline: ApiOperation<{ id: number; limit?: number }, TimelineItem[]>;
  fetchReviewRepositories: ApiOperation<undefined, string[]>;
  fetchReviewStatus: ApiOperation<{ id: number }, ReviewTaskStatus>;
  fetchGithubCommentPreview: ApiOperation<GithubCommentPreviewInput, GithubCommentPreview>;
  fetchGithubCommentPublicationHistory: ApiOperation<
    { id: number; page?: number; pageSize?: number; status?: string },
    GithubCommentPublicationHistory
  >;
  publishGithubComments: ApiOperation<{ id: number }, GithubCommentPublish>;
  submitHumanReview: ApiOperation<{ id: number; payload: HumanReviewRequest }, HumanReviewResponse>;
  updateFindingFeedback: ApiOperation<
    { id: number; findingId: number; payload: FindingFeedbackRequest },
    FindingFeedbackResponse
  >;
  retryReview: ApiOperation<{ id: number }, ReviewRetryResponse>;
  fetchGithubPullRequestOptions: ApiOperation<undefined, GithubPullRequestOptions>;
  triggerManualReview: ApiOperation<ManualReviewRequest, ManualReviewResponse>;
  fetchGithubIntegrationConfig: ApiOperation<undefined, GithubIntegrationConfig>;
  updateGithubIntegrationConfig: ApiOperation<GithubIntegrationConfigRequest, GithubIntegrationConfig>;
  fetchMysqlIntegrationConfig: ApiOperation<undefined, ServiceIntegrationConfig>;
  updateMysqlIntegrationConfig: ApiOperation<ServiceIntegrationConfigRequest, ServiceIntegrationConfig>;
  fetchRabbitMqIntegrationConfig: ApiOperation<undefined, ServiceIntegrationConfig>;
  updateRabbitMqIntegrationConfig: ApiOperation<ServiceIntegrationConfigRequest, ServiceIntegrationConfig>;
  fetchReviewPolicyConfig: ApiOperation<undefined, ReviewPolicyConfig>;
  updateReviewPolicyConfig: ApiOperation<ReviewPolicyConfigRequest, ReviewPolicyConfig>;
  fetchSystemSettings: ApiOperation<undefined, SystemSettings>;
  updateSystemSettings: ApiOperation<SystemSettingsRequest, SystemSettings>;
  reEncryptSecrets: ApiOperation<SecretReEncryptionRequest, SecretReEncryptionJob>;
  fetchSecretReEncryptionJob: ApiOperation<{ jobId: number }, SecretReEncryptionJob>;
  fetchSecretReEncryptionJobs: ApiOperation<
    { page?: number; pageSize?: number },
    PageResponse<SecretReEncryptionJob>
  >;
  fetchSecretReEncryptionJobItems: ApiOperation<
    { jobId: number; page?: number; pageSize?: number },
    PageResponse<SecretReEncryptionItem>
  >;
  pauseSecretReEncryptionJob: ApiOperation<{ jobId: number }, SecretReEncryptionJob>;
  resumeSecretReEncryptionJob: ApiOperation<{ jobId: number }, SecretReEncryptionJob>;
  fetchReviewRules: ApiOperation<undefined, ReviewRulesResponse>;
  fetchReviewCalibrationQueue: ApiOperation<
    { ruleId: string; limit?: number; includeIgnored?: boolean },
    ReviewCalibrationQueue
  >;
  createReviewRule: ApiOperation<ReviewRuleConfigRequest, ReviewRuleConfig>;
  updateReviewRule: ApiOperation<
    { id: string; expectedPolicyVersion: number; payload: ReviewRuleConfigRequest },
    ReviewRuleConfig
  >;
  updateReviewRuleStatus: ApiOperation<{ id: string; payload: ReviewRuleStatusRequest }, ReviewRuleConfig>;
  fetchReviewRuleVersions: ApiOperation<
    { id: string; cursor?: string; pageSize?: number },
    PageResponse<ReviewRulePolicyVersion>
  >;
  rollbackReviewRule: ApiOperation<
    { id: string; policyVersion: number; expectedPolicyVersion: number },
    ReviewRuleConfig
  >;
  fetchReviewStrategy: ApiOperation<undefined, ReviewStrategyPolicy>;
  fetchReviewStrategyVersions: ApiOperation<
    { cursor?: string; pageSize?: number },
    PageResponse<ReviewStrategyPolicy>
  >;
  updateReviewStrategyEnforcement: ApiOperation<ReviewEnforcementModeRequest, ReviewStrategyPolicy>;
  rollbackReviewStrategy: ApiOperation<
    { snapshotId: number; expectedSnapshotId: number },
    ReviewStrategyPolicy
  >;
  testGithubIntegrationConnection: ApiOperation<GithubIntegrationConfigRequest | undefined, ConnectionTestResult>;
  testMysqlConnection: ApiOperation<ServiceIntegrationConfigRequest | undefined, ConnectionTestResult>;
  testRabbitMqConnection: ApiOperation<ServiceIntegrationConfigRequest | undefined, ConnectionTestResult>;
  testReviewPolicyConnection: ApiOperation<ReviewPolicyConfigRequest | undefined, ConnectionTestResult>;
  fetchNotificationBindings: ApiOperation<NotificationBindingPageInput, PageResponse<NotificationBinding>>;
  createNotificationBinding: ApiOperation<NotificationBindingRequest, NotificationBinding>;
  updateNotificationBinding: ApiOperation<{ id: number; payload: NotificationBindingRequest }, NotificationBinding>;
  updateNotificationBindingStatus: ApiOperation<
    { id: number; payload: NotificationBindingStatusRequest },
    NotificationBinding
  >;
  deleteNotificationBinding: ApiOperation<{ id: number }, void>;
  testNotificationBinding: ApiOperation<{ id: number }, ConnectionTestResult>;
  fetchNotificationEvents: ApiOperation<NotificationPageInput, PageResponse<NotificationEvent>>;
  retryNotificationEvent: ApiOperation<{ id: number }, NotificationEvent>;
  fetchNotificationDeliveries: ApiOperation<NotificationPageInput, PageResponse<NotificationDelivery>>;
  fetchMessageQueueHealth: ApiOperation<undefined, MessageQueueHealth>;
  requeueMessageQueueTask: ApiOperation<{ taskId: number }, MessageQueueRequeueResponse>;
  fetchNotifications: ApiOperation<undefined, NotificationCenter>;
  fetchUsers: ApiOperation<UserPageInput, PageResponse<ManagedUser>>;
  fetchUserOperationAudits: ApiOperation<UserPageInput, PageResponse<UserOperationAudit>>;
  createUser: ApiOperation<UserCreateRequest, ManagedUser>;
  updateUserRole: ApiOperation<UserRoleInput, ManagedUser>;
  updateUserStatus: ApiOperation<UserStatusInput, ManagedUser>;
  reportFrontendPerformance: ApiOperation<FrontendPerformanceReport, void>;
};

type ApiEndpointMap = {
  [Operation in keyof ApiContract]: ApiEndpoint<
    ApiContract[Operation]["input"],
    ApiContract[Operation]["response"]
  >;
};

const idSegment = (value: number | string) => encodeURIComponent(String(value));

const apiEndpoints: ApiEndpointMap = {
  login: {
    method: "POST",
    path: () => "/api/v1/auth/login",
    body: input => input,
    validateResponse: isAuthResponse
  },
  register: {
    method: "POST",
    path: () => "/api/v1/auth/register",
    body: input => input,
    validateResponse: isAuthResponse
  },
  getCurrentUser: {
    path: () => "/api/v1/auth/me",
    validateResponse: isCurrentUser
  },
  changePassword: {
    method: "POST",
    path: () => "/api/v1/auth/password/change",
    body: input => input
  },
  resetRefreshToken: {
    method: "POST",
    path: () => "/api/v1/auth/refresh-token/reset",
    body: input => input,
    validateResponse: isAuthResponse
  },
  logout: {
    method: "POST",
    path: () => "/api/v1/auth/logout",
    body: input => input ?? {}
  },
  fetchDashboardOverview: generatedEndpoint("dashboardControllerGetOverview", {
    query: input => ({ llmTrendDays: input.llmTrendDays })
  }),
  fetchDashboardSummary: generatedEndpoint("dashboardControllerGetSummary", {}),
  fetchDashboardReviewTrend: generatedEndpoint("dashboardControllerGetReviewTrend", {}),
  fetchDashboardRiskDistribution: generatedEndpoint("dashboardControllerGetRiskDistribution", {}),
  fetchDashboardRules: generatedEndpoint("dashboardControllerGetRules", {}),
  fetchDashboardHighRiskReviews: generatedEndpoint("dashboardControllerGetHighRiskReviews", {}),
  fetchDashboardLlmQuality: generatedEndpoint("dashboardControllerGetLlmQuality", {
    query: input => ({ llmTrendDays: input.llmTrendDays })
  }),
  fetchSystemHealthSummary: {
    path: () => "/api/v1/system/health/summary"
  },
  fetchCacheStats: {
    path: () => "/api/v1/cache/stats"
  },
  cleanupDataRetention: {
    method: "POST",
    path: () => "/api/v1/config/data-retention/cleanup",
    body: input => input
  },
  fetchDataRetentionCleanupAudits: {
    path: () => "/api/v1/config/data-retention/cleanup-audits",
    query: input => ({
      page: input.page,
      pageSize: input.pageSize,
      mode: input.mode,
      status: input.status,
      backupReference: input.backupReference
    })
  },
  fetchReviews: generatedEndpoint("reviewControllerListReviews", {
    query: input => ({
      page: input.page,
      pageSize: input.pageSize,
      repository: input.repository,
      status: input.status,
      riskLevel: input.riskLevel,
      source: input.source,
      triggerSource: input.triggerSource,
      keyword: input.keyword,
      cursor: input.cursor
    })
  }),
  fetchReviewListSummary: generatedEndpoint("reviewControllerGetReviewListSummary", {
    query: input => ({
      repository: input.repository,
      status: input.status,
      riskLevel: input.riskLevel,
      source: input.source,
      triggerSource: input.triggerSource,
      keyword: input.keyword
    })
  }),
  fetchReviewDetail: {
    ...generatedEndpoint("reviewControllerGetReviewDetail", {
      path: input => ({ id: input.id })
    }),
    validateResponse: isReviewTaskSummary
  },
  fetchReviewFindings: generatedEndpoint("reviewControllerListReviewFindings", {
    path: input => ({ id: input.id }),
    query: input => ({
      page: input.page,
      pageSize: input.pageSize,
      severity: input.severity,
      category: input.category,
      feedbackStatus: input.feedbackStatus
    })
  }),
  fetchReviewChangedFiles: generatedEndpoint("reviewControllerListChangedFiles", {
    path: input => ({ id: input.id }),
    query: input => ({
      page: input.page,
      pageSize: input.pageSize,
      hasFinding: input.hasFinding
    })
  }),
  fetchReviewMissingTests: generatedEndpoint("reviewControllerListMissingTests", {
    path: input => ({ id: input.id }),
    query: input => ({ page: input.page, pageSize: input.pageSize })
  }),
  fetchReviewTimeline: generatedEndpoint("reviewControllerListReviewTimeline", {
    path: input => ({ id: input.id }),
    query: input => ({ limit: input.limit })
  }),
  fetchReviewRepositories: generatedEndpoint("reviewControllerListRepositories", {}),
  fetchReviewStatus: generatedEndpoint("reviewControllerGetReviewStatus", {
    path: input => ({ id: input.id })
  }),
  fetchGithubCommentPreview: generatedEndpoint("reviewControllerGetGithubCommentPreview", {
    path: input => ({ id: input.id }),
    query: input => ({
      page: input.page,
      pageSize: input.pageSize,
      commentableOnly: input.commentableOnly
    })
  }),
  fetchGithubCommentPublicationHistory: generatedEndpoint(
    "reviewControllerGetGithubCommentPublicationHistory",
    {
      path: input => ({ id: input.id }),
      query: input => ({ page: input.page, pageSize: input.pageSize, status: input.status })
    }
  ),
  publishGithubComments: generatedEndpoint("reviewControllerPublishGithubComments", {
    path: input => ({ id: input.id })
  }),
  submitHumanReview: generatedEndpoint("reviewControllerSubmitHumanReview", {
    path: input => ({ id: input.id }),
    body: input => input.payload
  }),
  updateFindingFeedback: generatedEndpoint("reviewControllerUpdateFindingFeedback", {
    path: input => ({ id: input.id, findingId: input.findingId }),
    body: input => input.payload
  }),
  retryReview: generatedEndpoint("reviewControllerRetryReview", {
    path: input => ({ id: input.id })
  }),
  fetchGithubPullRequestOptions: generatedEndpoint(
    "reviewControllerListConfiguredGithubPullRequests",
    {}
  ),
  triggerManualReview: generatedEndpoint("reviewControllerTriggerManualReview", {
    body: input => input
  }),
  fetchGithubIntegrationConfig: {
    path: () => "/api/v1/config/integrations/github",
    validateResponse: isGithubIntegrationConfig
  },
  updateGithubIntegrationConfig: {
    method: "PUT",
    path: () => "/api/v1/config/integrations/github",
    body: input => input,
    validateResponse: isGithubIntegrationConfig
  },
  fetchMysqlIntegrationConfig: {
    path: () => "/api/v1/config/integrations/mysql",
    validateResponse: isServiceIntegrationConfig
  },
  updateMysqlIntegrationConfig: {
    method: "PUT",
    path: () => "/api/v1/config/integrations/mysql",
    body: input => input,
    validateResponse: isServiceIntegrationConfig
  },
  fetchRabbitMqIntegrationConfig: {
    path: () => "/api/v1/config/integrations/rabbitmq",
    validateResponse: isServiceIntegrationConfig
  },
  updateRabbitMqIntegrationConfig: {
    method: "PUT",
    path: () => "/api/v1/config/integrations/rabbitmq",
    body: input => input,
    validateResponse: isServiceIntegrationConfig
  },
  fetchReviewPolicyConfig: {
    path: () => "/api/v1/config/review-policy",
    validateResponse: isReviewPolicyConfig
  },
  updateReviewPolicyConfig: {
    method: "PUT",
    path: () => "/api/v1/config/review-policy",
    body: input => input,
    validateResponse: isReviewPolicyConfig
  },
  fetchSystemSettings: {
    path: () => "/api/v1/config/system-settings"
  },
  updateSystemSettings: {
    method: "PUT",
    path: () => "/api/v1/config/system-settings",
    body: input => input
  },
  reEncryptSecrets: {
    method: "POST",
    path: () => "/api/v1/config/secrets/re-encryption",
    body: input => input,
    validateResponse: isSecretReEncryptionJob
  },
  fetchSecretReEncryptionJob: {
    path: input => `/api/v1/config/secrets/re-encryption/jobs/${idSegment(input.jobId)}`,
    validateResponse: isSecretReEncryptionJob
  },
  fetchSecretReEncryptionJobs: {
    path: () => "/api/v1/config/secrets/re-encryption/jobs",
    query: input => ({
      page: input.page,
      pageSize: input.pageSize
    }),
    validateResponse: isSecretReEncryptionJobPage
  },
  fetchSecretReEncryptionJobItems: {
    path: input => `/api/v1/config/secrets/re-encryption/jobs/${idSegment(input.jobId)}/items`,
    query: input => ({
      page: input.page,
      pageSize: input.pageSize
    }),
    validateResponse: isSecretReEncryptionItemPage
  },
  pauseSecretReEncryptionJob: {
    method: "POST",
    path: input => `/api/v1/config/secrets/re-encryption/jobs/${idSegment(input.jobId)}/pause`,
    validateResponse: isSecretReEncryptionJob
  },
  resumeSecretReEncryptionJob: {
    method: "POST",
    path: input => `/api/v1/config/secrets/re-encryption/jobs/${idSegment(input.jobId)}/resume`,
    validateResponse: isSecretReEncryptionJob
  },
  fetchReviewRules: {
    path: () => "/api/v1/config/review-rules"
  },
  fetchReviewCalibrationQueue: {
    path: () => "/api/v1/config/review-calibration/queue",
    query: input => ({
      ruleId: input.ruleId,
      limit: input.limit ?? 30,
      includeIgnored: input.includeIgnored ? "true" : "false"
    })
  },
  createReviewRule: {
    method: "POST",
    path: () => "/api/v1/config/review-rules",
    body: input => input
  },
  updateReviewRule: {
    method: "PUT",
    path: input => `/api/v1/config/review-rules/${idSegment(input.id)}`,
    query: input => ({ expectedPolicyVersion: input.expectedPolicyVersion }),
    body: input => input.payload
  },
  updateReviewRuleStatus: {
    method: "PUT",
    path: input => `/api/v1/config/review-rules/${idSegment(input.id)}/status`,
    body: input => input.payload
  },
  fetchReviewRuleVersions: {
    path: input => `/api/v1/config/review-rules/${idSegment(input.id)}/versions`,
    query: input => ({ cursor: input.cursor, pageSize: input.pageSize })
  },
  rollbackReviewRule: {
    method: "POST",
    path: input => `/api/v1/config/review-rules/${idSegment(input.id)}/versions/${idSegment(input.policyVersion)}/rollback`,
    body: input => ({ expectedPolicyVersion: input.expectedPolicyVersion })
  },
  fetchReviewStrategy: {
    path: () => "/api/v1/config/review-strategy"
  },
  fetchReviewStrategyVersions: {
    path: () => "/api/v1/config/review-strategy/versions",
    query: input => ({ cursor: input.cursor, pageSize: input.pageSize })
  },
  updateReviewStrategyEnforcement: {
    method: "PUT",
    path: () => "/api/v1/config/review-strategy/enforcement",
    body: input => input
  },
  rollbackReviewStrategy: {
    method: "POST",
    path: input => `/api/v1/config/review-strategy/versions/${idSegment(input.snapshotId)}/rollback`,
    body: input => ({ expectedSnapshotId: input.expectedSnapshotId })
  },
  testGithubIntegrationConnection: {
    method: "POST",
    path: () => "/api/v1/config/integrations/github/test",
    body: input => input
  },
  testMysqlConnection: {
    method: "POST",
    path: () => "/api/v1/config/integrations/mysql/test",
    body: input => input
  },
  testRabbitMqConnection: {
    method: "POST",
    path: () => "/api/v1/config/integrations/rabbitmq/test",
    body: input => input
  },
  testReviewPolicyConnection: {
    method: "POST",
    path: () => "/api/v1/config/review-policy/test",
    body: input => input
  },
  fetchNotificationBindings: generatedEndpoint(
    "notificationIntegrationControllerListBindings",
    {
      query: input => ({
        page: input.page ?? 1,
        pageSize: input.pageSize ?? 20,
        organization: input.organization,
        repository: input.repository,
        provider: input.provider
      })
    }
  ),
  createNotificationBinding: generatedEndpoint(
    "notificationIntegrationControllerCreateBinding",
    {
      body: input => input
    }
  ),
  updateNotificationBinding: generatedEndpoint(
    "notificationIntegrationControllerUpdateBinding",
    {
      path: input => ({ id: input.id }),
      body: input => input.payload
    }
  ),
  updateNotificationBindingStatus: generatedEndpoint(
    "notificationIntegrationControllerUpdateBindingStatus",
    {
      path: input => ({ id: input.id }),
      body: input => input.payload
    }
  ),
  deleteNotificationBinding: generatedEndpoint(
    "notificationIntegrationControllerDeleteBinding",
    {
      path: input => ({ id: input.id })
    }
  ),
  testNotificationBinding: generatedEndpoint(
    "notificationIntegrationControllerTestBinding",
    {
      path: input => ({ id: input.id })
    }
  ),
  fetchNotificationEvents: generatedEndpoint(
    "notificationIntegrationControllerListEvents",
    {
      query: input => ({
        page: input.page ?? 1,
        pageSize: input.pageSize ?? 20,
        status: input.status,
        taskId: input.taskId
      })
    }
  ),
  retryNotificationEvent: generatedEndpoint(
    "notificationIntegrationControllerRetryEvent",
    {
      path: input => ({ id: input.id })
    }
  ),
  fetchNotificationDeliveries: generatedEndpoint(
    "notificationIntegrationControllerListDeliveries",
    {
      query: input => ({
        page: input.page ?? 1,
        pageSize: input.pageSize ?? 20,
        status: input.status,
        taskId: input.taskId
      })
    }
  ),
  fetchMessageQueueHealth: {
    path: () => "/api/v1/message-queue/health"
  },
  requeueMessageQueueTask: {
    method: "POST",
    path: input => `/api/v1/message-queue/tasks/${idSegment(input.taskId)}/requeue`
  },
  fetchNotifications: generatedEndpoint("notificationControllerGetNotifications", {}),
  fetchUsers: {
    path: () => "/api/v1/users",
    query: input => ({
      page: input.page,
      pageSize: input.pageSize,
      role: input.role || undefined,
      status: input.status || undefined,
      keyword: input.keyword
    })
  },
  fetchUserOperationAudits: {
    path: () => "/api/v1/users/audits",
    query: input => ({ page: input.page, pageSize: input.pageSize })
  },
  createUser: {
    method: "POST",
    path: () => "/api/v1/users",
    body: input => input
  },
  updateUserRole: {
    method: "PUT",
    path: input => `/api/v1/users/${idSegment(input.id)}/role`,
    body: input => ({ role: input.role })
  },
  updateUserStatus: {
    method: "PUT",
    path: input => `/api/v1/users/${idSegment(input.id)}/status`,
    body: input => ({ status: input.status })
  },
  reportFrontendPerformance: {
    method: "POST",
    path: () => "/api/v1/observability/frontend/performance",
    body: input => input,
    observe: false
  }
};

export const apiRequest = async <Operation extends keyof ApiContract>(
  operation: Operation,
  input: ApiContract[Operation]["input"],
  requestOptions: ApiRequestOptions = {}
): Promise<ApiContract[Operation]["response"]> => {
  const endpoint = apiEndpoints[operation] as ApiEndpoint<
    ApiContract[Operation]["input"],
    ApiContract[Operation]["response"]
  >;
  const options: ClientRequestInit = {
    signal: requestOptions.signal,
    keepalive: requestOptions.keepalive,
    timeoutMs: requestOptions.timeoutMs
  };
  const method = endpoint.method ?? "GET";
  const path = endpoint.path(input);
  const observationPath = stableObservationPath(path);
  const shouldObserve = endpoint.observe !== false;
  if (method !== "GET") {
    options.method = method;
  }
  const payload = endpoint.body?.(input);
  if (payload !== undefined) {
    options.body = JSON.stringify(payload);
  }
  const startedAtMs = currentTimeMs();
  try {
    const response = await requestWithMeta<unknown>(
      path,
      endpoint.query?.(input),
      options
    );
    const data = validateApiResponse(String(operation), response.data, endpoint.validateResponse, response.status);
    if (shouldObserve) {
      observeFrontendApiRequest({
        operation: String(operation),
        path: observationPath,
        method,
        status: response.status,
        result: "success",
        traceId: response.traceId,
        responseBytes: response.responseBytes,
        startedAtMs,
        durationMs: currentTimeMs() - startedAtMs
      });
    }
    return data;
  } catch (error) {
    if (shouldObserve) {
      observeFrontendApiRequest({
        operation: String(operation),
        path: observationPath,
        method,
        status: error instanceof RequestError ? error.status : undefined,
        result: "failed",
        startedAtMs,
        durationMs: currentTimeMs() - startedAtMs
      });
    }
    throw error;
  }
};

const currentTimeMs = () => (typeof performance === "undefined" ? Date.now() : performance.now());

const stableObservationPath = (path: string) =>
  path
    .split("/")
    .map(segment => {
      if (/^\d+$/.test(segment)) {
        return "{id}";
      }
      if (/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(segment)) {
        return "{uuid}";
      }
      if (/^[0-9a-f]{32,64}$/i.test(segment)) {
        return "{hash}";
      }
      return segment;
    })
    .join("/");
