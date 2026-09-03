import { requestWithMeta } from "@/api/client";
import type { ClientRequestInit } from "@/api/client";
import type {
  ApiEndpoint,
  ApiOperation
} from "@/api/endpoint";
import { generatedEndpoint } from "@/api/generatedEndpoint";
import { observeFrontendApiRequest } from "@/observability/frontendPerformanceBuffer";
import type { FrontendPerformanceReport } from "@/observability/frontendPerformanceBuffer";
import type {
  ChangedFile,
  FindingFeedbackRequest,
  FindingFeedbackResponse,
  HumanReviewRequest,
  HumanReviewResponse,
  MissingTest,
  ReviewFinding,
  ReviewRetryResponse,
  ReviewTaskStatus,
  ReviewTaskSummary,
  TimelineItem
} from "@/api/generated/reviewDetailTypes";
import type { AuthResponse, CurrentUser, LoginRequest, PasswordChangeRequest, RefreshTokenResetRequest, RegisterRequest } from "@/api/auth";
import type { ManagedUser, UserCreateRequest, UserOperationAudit, UserRole, UserStatus } from "@/api/users";
import {
  isAuthResponse,
  isCurrentUser,
  isGithubChecksSetupStatus,
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
  EnterpriseIdentityBindingRequest,
  EnterpriseTenant,
  EnterpriseTenantCreateRequest,
  EnterpriseTenantMembershipRequest,
  EnterpriseTenantQuota,
  EnterpriseTenantQuotaRequest,
  EnterpriseTenantRepositoryRequest,
  EnterpriseTenantStatusRequest,
  LlmModelRelease,
  LlmModelReleaseAudit,
  LlmModelReleaseAuditExport,
  LlmModelReleaseAuditVerification,
  LlmModelReleaseCenter,
  LlmModelReleaseMetric,
  LlmModelReleaseDrift,
  LlmModelReleaseDriftRepair,
  LlmModelReleaseDriftRepairRequest,
  LlmModelReleaseRequest,
  LlmModelRollbackRequest,
  LlmEvaluationExport,
  LlmEvaluationReport,
  LlmEvaluationReportComparison,
  LlmEvaluationReportLifecycleRequest,
  LlmEvaluationRequest,
  LlmEvaluationRun,
  LlmEvaluationRunRequest,
  CacheStats,
  GithubCommentPreview,
  GithubCommentPublicationHistory,
  GithubCommentPublish,
  GithubChecksPolicyRequest,
  GithubChecksPreviewRequest,
  GithubChecksSetupStatus,
  GithubIntegrationConfig,
  GithubIntegrationConfigRequest,
  GithubPullRequestOptions,
  HighRiskReview,
  ManualReviewRequest,
  ManualReviewResponse,
  MessageQueueHealth,
  MessageQueueRequeueResponse,
  NotificationBinding,
  NotificationBindingRequest,
  NotificationBindingStatusRequest,
  NotificationCenter,
  NotificationReadRequest,
  NotificationReport,
  NotificationDelivery,
  NotificationEvent,
  PageResponse,
  ReviewPolicyConfig,
  ReviewPolicyConfigRequest,
  ReviewQuery,
  ReviewExecutionAttempt,
  ReviewExecutionAttemptResult,
  ReviewAttemptComparison,
  ReviewCalibrationQueue,
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
  ServiceIntegrationConfig,
  ServiceIntegrationConfigRequest,
  SystemHealthItem,
  SystemSettings,
  SystemSettingsRequest,
  RepositoryPolicyPreviewResponse,
  RepositorySuppressionRequest,
  RepositorySuppressionResponse,
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
  source?: string;
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

type EnterpriseTenantPageInput = {
  page?: number;
  pageSize?: number;
  status?: string;
};

type EnterpriseTenantCreateInput = EnterpriseTenantCreateRequest;
type EnterpriseTenantStatusInput = { tenantKey: string; payload: EnterpriseTenantStatusRequest };
type EnterpriseTenantMembershipInput = {
  tenantKey: string;
  payload: EnterpriseTenantMembershipRequest;
};
type EnterpriseTenantRepositoryInput = {
  tenantKey: string;
  payload: EnterpriseTenantRepositoryRequest;
};
type EnterpriseTenantIdentityInput = {
  tenantKey: string;
  payload: EnterpriseIdentityBindingRequest;
};
type EnterpriseTenantQuotaInput = { tenantKey: string; payload: EnterpriseTenantQuotaRequest };

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
  fetchReviewExecutionAttempts: ApiOperation<{ taskId: number }, ReviewExecutionAttempt[]>;
  fetchReviewExecutionAttemptResult: ApiOperation<
    { taskId: number; attemptId: number; page?: number; pageSize?: number },
    ReviewExecutionAttemptResult
  >;
  fetchReviewAttemptComparison: ApiOperation<
    { taskId: number; candidateAttemptId: number; baselineAttemptId?: number; page?: number; pageSize?: number },
    ReviewAttemptComparison
  >;
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
  fetchGithubChecksSetup: ApiOperation<
    { organization: string; repository: string },
    GithubChecksSetupStatus
  >;
  previewGithubChecks: ApiOperation<GithubChecksPreviewRequest, GithubChecksSetupStatus>;
  updateGithubChecksPolicy: ApiOperation<GithubChecksPolicyRequest, GithubChecksSetupStatus>;
  fetchMysqlIntegrationConfig: ApiOperation<undefined, ServiceIntegrationConfig>;
  updateMysqlIntegrationConfig: ApiOperation<ServiceIntegrationConfigRequest, ServiceIntegrationConfig>;
  fetchRabbitMqIntegrationConfig: ApiOperation<undefined, ServiceIntegrationConfig>;
  updateRabbitMqIntegrationConfig: ApiOperation<ServiceIntegrationConfigRequest, ServiceIntegrationConfig>;
  fetchReviewPolicyConfig: ApiOperation<undefined, ReviewPolicyConfig>;
  updateReviewPolicyConfig: ApiOperation<ReviewPolicyConfigRequest, ReviewPolicyConfig>;
  fetchRepositoryPolicyPreview: ApiOperation<
    { organization: string; repository: string; headSha?: string },
    RepositoryPolicyPreviewResponse
  >;
  fetchRepositorySuppressions: ApiOperation<
    { organization: string; repository: string; limit?: number },
    RepositorySuppressionResponse[]
  >;
  createRepositorySuppression: ApiOperation<RepositorySuppressionRequest, RepositorySuppressionResponse>;
  activateRepositorySuppression: ApiOperation<
    { id: number; reason?: string },
    RepositorySuppressionResponse
  >;
  revokeRepositorySuppression: ApiOperation<
    { id: number; reason?: string },
    RepositorySuppressionResponse
  >;
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
  fetchLlmModelReleaseCenter: ApiOperation<{ trendDays?: number }, LlmModelReleaseCenter>;
  fetchLlmModelReleaseRuntimeMetrics: ApiOperation<
    { releaseKey?: string; days?: number; limit?: number },
    LlmModelReleaseMetric[]
  >;
  fetchLlmModelReleaseDrift: ApiOperation<undefined, LlmModelReleaseDrift>;
  repairLlmModelReleaseDrift: ApiOperation<LlmModelReleaseDriftRepairRequest, LlmModelReleaseDriftRepair>;
  fetchLlmModelReleaseAudits: ApiOperation<{
    releaseId?: number;
    releaseKey?: string;
    operator?: string;
    action?: string;
    from?: string;
    to?: string;
    page?: number;
    pageSize?: number;
  }, PageResponse<LlmModelReleaseAudit>>;
  verifyLlmModelReleaseAudit: ApiOperation<{ auditId: number }, LlmModelReleaseAuditVerification>;
  exportLlmModelReleaseAudits: ApiOperation<{
    releaseId?: number;
    releaseKey?: string;
    operator?: string;
    action?: string;
    from?: string;
    to?: string;
    format?: string;
  }, LlmModelReleaseAuditExport>;
  registerLlmModelShadowRelease: ApiOperation<LlmModelReleaseRequest, LlmModelRelease>;
  promoteLlmModelRelease: ApiOperation<LlmModelReleaseRequest, LlmModelRelease>;
  createLlmEvaluationReport: ApiOperation<LlmEvaluationRequest, LlmEvaluationReport>;
  startLlmEvaluationRun: ApiOperation<LlmEvaluationRunRequest, LlmEvaluationRun>;
  fetchLlmEvaluationRun: ApiOperation<{ runId: string }, LlmEvaluationRun>;
  cancelLlmEvaluationRun: ApiOperation<{ runId: string }, LlmEvaluationRun>;
  fetchLlmEvaluationReports: ApiOperation<{ limit?: number }, LlmEvaluationReport[]>;
  fetchLlmEvaluationReport: ApiOperation<{ reportId: number }, LlmEvaluationReport>;
  compareLlmEvaluationReports: ApiOperation<
    { reportId: number; candidateReportId: number },
    LlmEvaluationReportComparison
  >;
  exportLlmEvaluationReport: ApiOperation<{ reportId: number; format?: string }, LlmEvaluationExport>;
  transitionLlmEvaluationReportLifecycle: ApiOperation<
    { reportId: number; payload: LlmEvaluationReportLifecycleRequest },
    LlmEvaluationReport
  >;
  rollbackLlmModelRelease: ApiOperation<
    { releaseId: number; payload: LlmModelRollbackRequest },
    LlmModelRelease
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
  markNotificationRead: ApiOperation<NotificationReadRequest, void>;
  fetchNotificationReadKeys: ApiOperation<undefined, string[]>;
  fetchNotificationReport: ApiOperation<{ period?: string }, NotificationReport>;
  fetchUsers: ApiOperation<UserPageInput, PageResponse<ManagedUser>>;
  fetchUserOperationAudits: ApiOperation<UserPageInput, PageResponse<UserOperationAudit>>;
  createUser: ApiOperation<UserCreateRequest, ManagedUser>;
  updateUserRole: ApiOperation<UserRoleInput, ManagedUser>;
  updateUserStatus: ApiOperation<UserStatusInput, ManagedUser>;
  fetchEnterpriseTenants: ApiOperation<EnterpriseTenantPageInput, PageResponse<EnterpriseTenant>>;
  fetchEnterpriseTenant: ApiOperation<{ tenantKey: string }, EnterpriseTenant>;
  createEnterpriseTenant: ApiOperation<EnterpriseTenantCreateInput, EnterpriseTenant>;
  updateEnterpriseTenantStatus: ApiOperation<EnterpriseTenantStatusInput, EnterpriseTenant>;
  bindEnterpriseTenantMembership: ApiOperation<EnterpriseTenantMembershipInput, void>;
  bindEnterpriseTenantRepository: ApiOperation<EnterpriseTenantRepositoryInput, void>;
  bindEnterpriseTenantIdentity: ApiOperation<EnterpriseTenantIdentityInput, void>;
  fetchEnterpriseTenantQuota: ApiOperation<{ tenantKey: string }, EnterpriseTenantQuota>;
  updateEnterpriseTenantQuota: ApiOperation<EnterpriseTenantQuotaInput, EnterpriseTenantQuota>;
  reportFrontendPerformance: ApiOperation<FrontendPerformanceReport, void>;
};

type ApiEndpointMap = {
  [Operation in keyof ApiContract]: ApiEndpoint<
    ApiContract[Operation]["input"],
    ApiContract[Operation]["response"]
  >;
};

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
  fetchSystemHealthSummary: generatedEndpoint("systemHealthControllerGetSystemHealthSummary", {}),
  fetchCacheStats: generatedEndpoint("cacheStatsControllerGetStats", {}),
  cleanupDataRetention: generatedEndpoint("dataRetentionControllerCleanup", {
    body: input => input
  }),
  fetchDataRetentionCleanupAudits: generatedEndpoint("dataRetentionControllerListCleanupAudits", {
    query: input => ({
      page: input.page,
      pageSize: input.pageSize,
      mode: input.mode,
      status: input.status,
      backupReference: input.backupReference
    })
  }),
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
      feedbackStatus: input.feedbackStatus,
      source: input.source
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
  fetchReviewExecutionAttempts: generatedEndpoint("reviewExecutionAttemptControllerList", {
    path: input => ({ taskId: input.taskId })
  }),
  fetchReviewExecutionAttemptResult: generatedEndpoint("reviewExecutionAttemptControllerGetResult", {
    path: input => ({ taskId: input.taskId, attemptId: input.attemptId }),
    query: input => ({ page: input.page, pageSize: input.pageSize })
  }),
  fetchReviewAttemptComparison: generatedEndpoint("reviewExecutionAttemptComparisonControllerCompare", {
    path: input => ({ taskId: input.taskId, candidateAttemptId: input.candidateAttemptId }),
    query: input => ({
      baselineAttemptId: input.baselineAttemptId,
      page: input.page,
      pageSize: input.pageSize
    })
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
    ...generatedEndpoint("systemConfigControllerGetGithubIntegration", {}),
    validateResponse: isGithubIntegrationConfig
  },
  updateGithubIntegrationConfig: {
    ...generatedEndpoint("systemConfigControllerUpdateGithubIntegration", {
      body: input => input
    }),
    validateResponse: isGithubIntegrationConfig
  },
  fetchGithubChecksSetup: {
    ...generatedEndpoint("systemConfigControllerGetGithubChecksSetup", {
      query: input => ({ organization: input.organization, repository: input.repository })
    }),
    validateResponse: isGithubChecksSetupStatus
  },
  previewGithubChecks: {
    ...generatedEndpoint("systemConfigControllerPreviewGithubChecks", {
      body: input => input
    }),
    validateResponse: isGithubChecksSetupStatus
  },
  updateGithubChecksPolicy: {
    ...generatedEndpoint("systemConfigControllerUpdateGithubChecksPolicy", {
      body: input => input
    }),
    validateResponse: isGithubChecksSetupStatus
  },
  fetchMysqlIntegrationConfig: {
    ...generatedEndpoint("systemConfigControllerGetMysqlIntegration", {}),
    validateResponse: isServiceIntegrationConfig
  },
  updateMysqlIntegrationConfig: {
    ...generatedEndpoint("systemConfigControllerUpdateMysqlIntegration", {
      body: input => input
    }),
    validateResponse: isServiceIntegrationConfig
  },
  fetchRabbitMqIntegrationConfig: {
    ...generatedEndpoint("systemConfigControllerGetRabbitMqIntegration", {}),
    validateResponse: isServiceIntegrationConfig
  },
  updateRabbitMqIntegrationConfig: {
    ...generatedEndpoint("systemConfigControllerUpdateRabbitMqIntegration", {
      body: input => input
    }),
    validateResponse: isServiceIntegrationConfig
  },
  fetchReviewPolicyConfig: {
    ...generatedEndpoint("systemConfigControllerGetReviewPolicy", {}),
    validateResponse: isReviewPolicyConfig
  },
  updateReviewPolicyConfig: {
    ...generatedEndpoint("systemConfigControllerUpdateReviewPolicy", {
      body: input => input
    }),
    validateResponse: isReviewPolicyConfig
  },
  fetchRepositoryPolicyPreview: generatedEndpoint("repositoryPolicyControllerPreview", {
    query: input => ({
      organization: input.organization,
      repository: input.repository,
      headSha: input.headSha
    })
  }),
  fetchRepositorySuppressions: generatedEndpoint("repositoryPolicyControllerListSuppressions", {
    query: input => ({
      organization: input.organization,
      repository: input.repository,
      limit: input.limit
    })
  }),
  createRepositorySuppression: generatedEndpoint("repositoryPolicyControllerCreateSuppression", {
    body: input => input
  }),
  activateRepositorySuppression: generatedEndpoint("repositoryPolicyControllerActivate", {
    path: input => ({ id: input.id }),
    query: input => ({ reason: input.reason })
  }),
  revokeRepositorySuppression: generatedEndpoint("repositoryPolicyControllerRevoke", {
    path: input => ({ id: input.id }),
    query: input => ({ reason: input.reason })
  }),
  fetchSystemSettings: generatedEndpoint("systemConfigControllerGetSystemSettings", {}),
  updateSystemSettings: generatedEndpoint("systemConfigControllerUpdateSystemSettings", {
    body: input => input
  }),
  reEncryptSecrets: {
    ...generatedEndpoint("systemConfigControllerReEncryptSecrets", {
      body: input => input
    }),
    validateResponse: isSecretReEncryptionJob
  },
  fetchSecretReEncryptionJob: {
    ...generatedEndpoint("systemConfigControllerGetSecretReEncryptionJob", {
      path: input => ({ jobId: input.jobId })
    }),
    validateResponse: isSecretReEncryptionJob
  },
  fetchSecretReEncryptionJobs: {
    ...generatedEndpoint("systemConfigControllerListSecretReEncryptionJobs", {
      query: input => ({ page: input.page, pageSize: input.pageSize })
    }),
    validateResponse: isSecretReEncryptionJobPage
  },
  fetchSecretReEncryptionJobItems: {
    ...generatedEndpoint("systemConfigControllerListSecretReEncryptionJobItems", {
      path: input => ({ jobId: input.jobId }),
      query: input => ({ page: input.page, pageSize: input.pageSize })
    }),
    validateResponse: isSecretReEncryptionItemPage
  },
  pauseSecretReEncryptionJob: {
    ...generatedEndpoint("systemConfigControllerPauseSecretReEncryptionJob", {
      path: input => ({ jobId: input.jobId })
    }),
    validateResponse: isSecretReEncryptionJob
  },
  resumeSecretReEncryptionJob: {
    ...generatedEndpoint("systemConfigControllerResumeSecretReEncryptionJob", {
      path: input => ({ jobId: input.jobId })
    }),
    validateResponse: isSecretReEncryptionJob
  },
  fetchReviewRules: generatedEndpoint("systemConfigControllerGetReviewRules", {}),
  fetchReviewCalibrationQueue: generatedEndpoint("reviewCalibrationControllerGetReviewCalibrationQueue", {
    query: input => ({
      ruleId: input.ruleId,
      limit: input.limit ?? 30,
      includeIgnored: input.includeIgnored ?? false
    })
  }),
  fetchLlmModelReleaseCenter: generatedEndpoint("reviewCalibrationControllerGetModelReleaseCenter", {
    query: input => ({ trendDays: input.trendDays })
  }),
  fetchLlmModelReleaseRuntimeMetrics: generatedEndpoint("reviewCalibrationControllerListModelReleaseRuntimeMetrics", {
    query: input => ({ releaseKey: input.releaseKey, days: input.days, limit: input.limit })
  }),
  fetchLlmModelReleaseDrift: generatedEndpoint("reviewCalibrationControllerInspectModelReleaseDrift", {}),
  repairLlmModelReleaseDrift: generatedEndpoint("reviewCalibrationControllerRepairModelReleaseDrift", {
    body: input => input
  }),
  fetchLlmModelReleaseAudits: generatedEndpoint("reviewCalibrationControllerListModelReleaseAudits", {
    query: input => ({
      releaseId: input.releaseId,
      releaseKey: input.releaseKey,
      operator: input.operator,
      action: input.action,
      from: input.from,
      to: input.to,
      page: input.page,
      pageSize: input.pageSize
    })
  }),
  verifyLlmModelReleaseAudit: generatedEndpoint("reviewCalibrationControllerVerifyModelReleaseAudit", {
    path: input => ({ auditId: input.auditId })
  }),
  exportLlmModelReleaseAudits: generatedEndpoint("reviewCalibrationControllerExportModelReleaseAudits", {
    query: input => ({
      releaseId: input.releaseId,
      releaseKey: input.releaseKey,
      operator: input.operator,
      action: input.action,
      from: input.from,
      to: input.to,
      format: input.format
    })
  }),
  registerLlmModelShadowRelease: generatedEndpoint("reviewCalibrationControllerRegisterShadowRelease", {
    body: input => input
  }),
  promoteLlmModelRelease: generatedEndpoint("reviewCalibrationControllerPromoteModelRelease", {
    body: input => input
  }),
  createLlmEvaluationReport: generatedEndpoint("reviewCalibrationControllerCreateEvaluationReport", {
    body: input => input
  }),
  startLlmEvaluationRun: generatedEndpoint("reviewCalibrationControllerStartEvaluationRun", {
    body: input => input
  }),
  fetchLlmEvaluationRun: generatedEndpoint("reviewCalibrationControllerGetEvaluationRun", {
    path: input => ({ runId: input.runId })
  }),
  cancelLlmEvaluationRun: generatedEndpoint("reviewCalibrationControllerCancelEvaluationRun", {
    path: input => ({ runId: input.runId })
  }),
  fetchLlmEvaluationReports: generatedEndpoint("reviewCalibrationControllerListEvaluationReports", {
    query: input => ({ limit: input.limit })
  }),
  fetchLlmEvaluationReport: generatedEndpoint("reviewCalibrationControllerGetEvaluationReport", {
    path: input => ({ reportId: input.reportId })
  }),
  compareLlmEvaluationReports: generatedEndpoint("reviewCalibrationControllerCompareEvaluationReports", {
    path: input => ({ reportId: input.reportId, candidateReportId: input.candidateReportId })
  }),
  exportLlmEvaluationReport: generatedEndpoint("reviewCalibrationControllerExportEvaluationReport", {
    path: input => ({ reportId: input.reportId }),
    query: input => ({ format: input.format })
  }),
  transitionLlmEvaluationReportLifecycle: generatedEndpoint("reviewCalibrationControllerTransitionEvaluationReportLifecycle", {
    path: input => ({ reportId: input.reportId }),
    body: input => input.payload
  }),
  rollbackLlmModelRelease: generatedEndpoint("reviewCalibrationControllerRollbackModelRelease", {
    path: input => ({ releaseId: input.releaseId }),
    body: input => input.payload
  }),
  createReviewRule: generatedEndpoint("systemConfigControllerCreateReviewRule", {
    body: input => input
  }),
  updateReviewRule: generatedEndpoint("systemConfigControllerUpdateReviewRule", {
    path: input => ({ id: input.id }),
    query: input => ({ expectedPolicyVersion: input.expectedPolicyVersion }),
    body: input => input.payload
  }),
  updateReviewRuleStatus: generatedEndpoint("systemConfigControllerUpdateReviewRuleStatus", {
    path: input => ({ id: input.id }),
    body: input => input.payload
  }),
  fetchReviewRuleVersions: generatedEndpoint("systemConfigControllerGetReviewRuleVersions", {
    path: input => ({ id: input.id }),
    query: input => ({
      cursor: input.cursor ? Number(input.cursor) : undefined,
      pageSize: input.pageSize
    })
  }),
  rollbackReviewRule: generatedEndpoint("systemConfigControllerRollbackReviewRule", {
    path: input => ({ id: input.id, policyVersion: input.policyVersion }),
    body: input => ({ expectedPolicyVersion: input.expectedPolicyVersion })
  }),
  fetchReviewStrategy: generatedEndpoint("systemConfigControllerGetReviewStrategyPolicy", {}),
  fetchReviewStrategyVersions: generatedEndpoint("systemConfigControllerGetReviewStrategyVersions", {
    query: input => ({
      cursor: input.cursor ? Number(input.cursor) : undefined,
      pageSize: input.pageSize
    })
  }),
  updateReviewStrategyEnforcement: generatedEndpoint("systemConfigControllerPromoteReviewStrategy", {
    body: input => input
  }),
  rollbackReviewStrategy: generatedEndpoint("systemConfigControllerRollbackReviewStrategy", {
    path: input => ({ snapshotId: input.snapshotId }),
    body: input => ({ expectedSnapshotId: input.expectedSnapshotId })
  }),
  testGithubIntegrationConnection: generatedEndpoint("systemConfigControllerTestGithubIntegration", {
    body: input => input
  }),
  testMysqlConnection: generatedEndpoint("systemConfigControllerTestMysqlConnection", {
    body: input => input
  }),
  testRabbitMqConnection: generatedEndpoint("systemConfigControllerTestRabbitMqConnection", {
    body: input => input
  }),
  testReviewPolicyConnection: generatedEndpoint("systemConfigControllerTestReviewPolicy", {
    body: input => input
  }),
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
  fetchMessageQueueHealth: generatedEndpoint("messageQueueHealthControllerGetHealth", {}),
  requeueMessageQueueTask: generatedEndpoint("messageQueueHealthControllerRequeueTask", {
    path: input => ({ taskId: input.taskId })
  }),
  fetchNotifications: generatedEndpoint("notificationControllerGetNotifications", {}),
  markNotificationRead: generatedEndpoint("notificationControllerMarkRead", {
    body: input => input
  }),
  fetchNotificationReadKeys: generatedEndpoint("notificationControllerReadKeys", {}),
  fetchNotificationReport: generatedEndpoint("notificationControllerReport", {
    query: input => ({ period: input.period })
  }),
  fetchUsers: generatedEndpoint("userManagementControllerListUsers", {
    query: input => ({
      page: input.page,
      pageSize: input.pageSize,
      role: input.role || undefined,
      status: input.status || undefined,
      keyword: input.keyword
    })
  }),
  fetchUserOperationAudits: generatedEndpoint("userManagementControllerListOperationAudits", {
    query: input => ({ page: input.page, pageSize: input.pageSize })
  }),
  createUser: generatedEndpoint("userManagementControllerCreateUser", {
    body: input => input
  }),
  updateUserRole: generatedEndpoint("userManagementControllerUpdateRole", {
    path: input => ({ id: input.id }),
    body: input => ({ role: input.role })
  }),
  updateUserStatus: generatedEndpoint("userManagementControllerUpdateStatus", {
    path: input => ({ id: input.id }),
    body: input => ({ status: input.status })
  }),
  fetchEnterpriseTenants: generatedEndpoint("enterpriseTenantControllerList", {
    query: input => ({
      page: input.page,
      pageSize: input.pageSize,
      status: input.status || undefined
    })
  }),
  fetchEnterpriseTenant: generatedEndpoint("enterpriseTenantControllerGet", {
    path: input => ({ tenantKey: input.tenantKey })
  }),
  createEnterpriseTenant: generatedEndpoint("enterpriseTenantControllerCreate", {
    body: input => input
  }),
  updateEnterpriseTenantStatus: generatedEndpoint("enterpriseTenantControllerUpdateStatus", {
    path: input => ({ tenantKey: input.tenantKey }),
    body: input => input.payload
  }),
  bindEnterpriseTenantMembership: generatedEndpoint("enterpriseTenantControllerPutMembership", {
    path: input => ({ tenantKey: input.tenantKey }),
    body: input => input.payload
  }),
  bindEnterpriseTenantRepository: generatedEndpoint("enterpriseTenantControllerPutRepository", {
    path: input => ({ tenantKey: input.tenantKey }),
    body: input => input.payload
  }),
  bindEnterpriseTenantIdentity: generatedEndpoint("enterpriseTenantControllerPutIdentity", {
    path: input => ({ tenantKey: input.tenantKey }),
    body: input => input.payload
  }),
  fetchEnterpriseTenantQuota: generatedEndpoint("enterpriseTenantQuotaControllerGet", {
    path: input => ({ tenantKey: input.tenantKey })
  }),
  updateEnterpriseTenantQuota: generatedEndpoint("enterpriseTenantQuotaControllerUpdate", {
    path: input => ({ tenantKey: input.tenantKey }),
    body: input => input.payload
  }),
  reportFrontendPerformance: {
    ...generatedEndpoint("frontendPerformanceControllerRecordPerformance", {
      body: input => input
    }),
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
