import { requestWithMeta } from "@/api/client";
import { observeFrontendApiRequest } from "@/observability/frontendPerformanceBuffer";
import type { FrontendPerformanceReport } from "@/observability/frontendPerformanceBuffer";
import type { AuthResponse, CurrentUser, LoginRequest, RefreshTokenResetRequest, RegisterRequest } from "@/api/auth";
import type { ManagedUser, UserCreateRequest, UserOperationAudit, UserRole, UserStatus } from "@/api/users";
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
  ReviewRetryResponse,
  ReviewRuleConfig,
  ReviewRuleConfigRequest,
  ReviewRulesResponse,
  ReviewRuleStatusRequest,
  SecretReEncryptionRequest,
  SecretReEncryptionResponse,
  ReviewTrendPoint,
  ReviewTask,
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

type HttpMethod = "GET" | "POST" | "PUT" | "DELETE";
type QueryParams = Record<string, string | number | undefined>;

type ApiOperation<Input, Response> = {
  input: Input;
  response: Response;
};

type ApiEndpoint<Input> = {
  method?: HttpMethod;
  path: (input: Input) => string;
  query?: (input: Input) => QueryParams;
  body?: (input: Input) => unknown;
  observe?: boolean;
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
  reEncryptSecrets: ApiOperation<SecretReEncryptionRequest, SecretReEncryptionResponse>;
  fetchReviewRules: ApiOperation<undefined, ReviewRulesResponse>;
  createReviewRule: ApiOperation<ReviewRuleConfigRequest, ReviewRuleConfig>;
  updateReviewRule: ApiOperation<{ id: string; payload: ReviewRuleConfigRequest }, ReviewRuleConfig>;
  updateReviewRuleStatus: ApiOperation<{ id: string; payload: ReviewRuleStatusRequest }, ReviewRuleConfig>;
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
  [Operation in keyof ApiContract]: ApiEndpoint<ApiContract[Operation]["input"]>;
};

const idSegment = (value: number | string) => encodeURIComponent(String(value));

const notificationQuery = (input: NotificationPageInput = {}) => ({
  page: input.page ?? 1,
  pageSize: input.pageSize ?? 20,
  status: input.status,
  taskId: input.taskId
});

const apiEndpoints: ApiEndpointMap = {
  login: {
    method: "POST",
    path: () => "/api/v1/auth/login",
    body: input => input
  },
  register: {
    method: "POST",
    path: () => "/api/v1/auth/register",
    body: input => input
  },
  getCurrentUser: {
    path: () => "/api/v1/auth/me"
  },
  resetRefreshToken: {
    method: "POST",
    path: () => "/api/v1/auth/refresh-token/reset",
    body: input => input
  },
  logout: {
    method: "POST",
    path: () => "/api/v1/auth/logout",
    body: input => input ?? {}
  },
  fetchDashboardOverview: {
    path: () => "/api/v1/dashboard/overview",
    query: input => ({ llmTrendDays: input.llmTrendDays })
  },
  fetchDashboardSummary: {
    path: () => "/api/v1/dashboard/summary"
  },
  fetchDashboardReviewTrend: {
    path: () => "/api/v1/dashboard/review-trend"
  },
  fetchDashboardRiskDistribution: {
    path: () => "/api/v1/dashboard/risk-distribution"
  },
  fetchDashboardRules: {
    path: () => "/api/v1/dashboard/rules"
  },
  fetchDashboardHighRiskReviews: {
    path: () => "/api/v1/dashboard/high-risk-reviews"
  },
  fetchDashboardLlmQuality: {
    path: () => "/api/v1/dashboard/llm-quality",
    query: input => ({ llmTrendDays: input.llmTrendDays })
  },
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
  fetchReviews: {
    path: () => "/api/v1/reviews",
    query: input => ({
      page: input.page,
      pageSize: input.pageSize,
      repository: input.repository,
      status: input.status,
      riskLevel: input.riskLevel,
      source: input.source,
      triggerSource: input.triggerSource,
      keyword: input.keyword,
      cursorCreatedAt: input.cursorCreatedAt,
      cursorId: input.cursorId
    })
  },
  fetchReviewDetail: {
    path: input => `/api/v1/reviews/${idSegment(input.id)}`
  },
  fetchReviewFindings: {
    path: input => `/api/v1/reviews/${idSegment(input.id)}/findings`,
    query: input => ({
      page: input.page,
      pageSize: input.pageSize,
      severity: input.severity,
      category: input.category,
      feedbackStatus: input.feedbackStatus
    })
  },
  fetchReviewChangedFiles: {
    path: input => `/api/v1/reviews/${idSegment(input.id)}/changed-files`,
    query: input => ({
      page: input.page,
      pageSize: input.pageSize,
      hasFinding: input.hasFinding === undefined ? undefined : String(input.hasFinding)
    })
  },
  fetchReviewMissingTests: {
    path: input => `/api/v1/reviews/${idSegment(input.id)}/missing-tests`,
    query: input => ({ page: input.page, pageSize: input.pageSize })
  },
  fetchReviewTimeline: {
    path: input => `/api/v1/reviews/${idSegment(input.id)}/timeline`,
    query: input => ({ limit: input.limit })
  },
  fetchReviewRepositories: {
    path: () => "/api/v1/reviews/repositories"
  },
  fetchReviewStatus: {
    path: input => `/api/v1/reviews/${idSegment(input.id)}/status`
  },
  fetchGithubCommentPreview: {
    path: input => `/api/v1/reviews/${idSegment(input.id)}/github-comments/preview`,
    query: input => ({
      page: input.page,
      pageSize: input.pageSize,
      commentableOnly: input.commentableOnly === undefined ? undefined : String(input.commentableOnly)
    })
  },
  fetchGithubCommentPublicationHistory: {
    path: input => `/api/v1/reviews/${idSegment(input.id)}/github-comments/publications`,
    query: input => ({ page: input.page, pageSize: input.pageSize, status: input.status })
  },
  publishGithubComments: {
    method: "POST",
    path: input => `/api/v1/reviews/${idSegment(input.id)}/github-comments`
  },
  submitHumanReview: {
    method: "POST",
    path: input => `/api/v1/reviews/${idSegment(input.id)}/human-review`,
    body: input => input.payload
  },
  updateFindingFeedback: {
    method: "POST",
    path: input => `/api/v1/reviews/${idSegment(input.id)}/findings/${idSegment(input.findingId)}/feedback`,
    body: input => input.payload
  },
  retryReview: {
    method: "POST",
    path: input => `/api/v1/reviews/${idSegment(input.id)}/retry`
  },
  fetchGithubPullRequestOptions: {
    path: () => "/api/v1/reviews/github/pull-requests"
  },
  triggerManualReview: {
    method: "POST",
    path: () => "/api/v1/reviews/manual",
    body: input => input
  },
  fetchGithubIntegrationConfig: {
    path: () => "/api/v1/config/integrations/github"
  },
  updateGithubIntegrationConfig: {
    method: "PUT",
    path: () => "/api/v1/config/integrations/github",
    body: input => input
  },
  fetchMysqlIntegrationConfig: {
    path: () => "/api/v1/config/integrations/mysql"
  },
  updateMysqlIntegrationConfig: {
    method: "PUT",
    path: () => "/api/v1/config/integrations/mysql",
    body: input => input
  },
  fetchRabbitMqIntegrationConfig: {
    path: () => "/api/v1/config/integrations/rabbitmq"
  },
  updateRabbitMqIntegrationConfig: {
    method: "PUT",
    path: () => "/api/v1/config/integrations/rabbitmq",
    body: input => input
  },
  fetchReviewPolicyConfig: {
    path: () => "/api/v1/config/review-policy"
  },
  updateReviewPolicyConfig: {
    method: "PUT",
    path: () => "/api/v1/config/review-policy",
    body: input => input
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
    body: input => input
  },
  fetchReviewRules: {
    path: () => "/api/v1/config/review-rules"
  },
  createReviewRule: {
    method: "POST",
    path: () => "/api/v1/config/review-rules",
    body: input => input
  },
  updateReviewRule: {
    method: "PUT",
    path: input => `/api/v1/config/review-rules/${idSegment(input.id)}`,
    body: input => input.payload
  },
  updateReviewRuleStatus: {
    method: "PUT",
    path: input => `/api/v1/config/review-rules/${idSegment(input.id)}/status`,
    body: input => input.payload
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
  fetchNotificationBindings: {
    path: () => "/api/v1/config/notification-bindings",
    query: input => ({
      page: input.page ?? 1,
      pageSize: input.pageSize ?? 20,
      organization: input.organization,
      repository: input.repository,
      provider: input.provider
    })
  },
  createNotificationBinding: {
    method: "POST",
    path: () => "/api/v1/config/notification-bindings",
    body: input => input
  },
  updateNotificationBinding: {
    method: "PUT",
    path: input => `/api/v1/config/notification-bindings/${idSegment(input.id)}`,
    body: input => input.payload
  },
  updateNotificationBindingStatus: {
    method: "PUT",
    path: input => `/api/v1/config/notification-bindings/${idSegment(input.id)}/status`,
    body: input => input.payload
  },
  deleteNotificationBinding: {
    method: "DELETE",
    path: input => `/api/v1/config/notification-bindings/${idSegment(input.id)}`
  },
  testNotificationBinding: {
    method: "POST",
    path: input => `/api/v1/config/notification-bindings/${idSegment(input.id)}/test`
  },
  fetchNotificationEvents: {
    path: () => "/api/v1/notification-events",
    query: notificationQuery
  },
  retryNotificationEvent: {
    method: "POST",
    path: input => `/api/v1/notification-events/${idSegment(input.id)}/retry`
  },
  fetchNotificationDeliveries: {
    path: () => "/api/v1/notification-deliveries",
    query: notificationQuery
  },
  fetchMessageQueueHealth: {
    path: () => "/api/v1/message-queue/health"
  },
  requeueMessageQueueTask: {
    method: "POST",
    path: input => `/api/v1/message-queue/tasks/${idSegment(input.taskId)}/requeue`
  },
  fetchNotifications: {
    path: () => "/api/v1/notifications"
  },
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
  input: ApiContract[Operation]["input"]
): Promise<ApiContract[Operation]["response"]> => {
  const endpoint = apiEndpoints[operation] as ApiEndpoint<ApiContract[Operation]["input"]>;
  const options: RequestInit = {};
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
    const response = await requestWithMeta<ApiContract[Operation]["response"]>(
      path,
      endpoint.query?.(input),
      options
    );
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
    return response.data;
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
