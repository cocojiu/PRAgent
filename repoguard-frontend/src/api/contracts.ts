import { request } from "@/api/client";
import type { AuthResponse, CurrentUser, LoginRequest, RegisterRequest } from "@/api/auth";
import type { ManagedUser, UserCreateRequest, UserOperationAudit, UserRole, UserStatus } from "@/api/users";
import type {
  ConnectionTestResult,
  DashboardOverview,
  FindingFeedbackRequest,
  FindingFeedbackResponse,
  ChangedFile,
  GithubCommentPreview,
  GithubCommentPublicationHistory,
  GithubCommentPublish,
  GithubIntegrationConfig,
  GithubIntegrationConfigRequest,
  GithubPullRequestOptions,
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
  ReviewTask,
  ReviewTaskDetail,
  ReviewTaskStatus,
  ServiceIntegrationConfig,
  ServiceIntegrationConfigRequest,
  SystemSettings,
  SystemSettingsRequest
} from "@/types";

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
};

type NotificationPageInput = {
  page?: number;
  pageSize?: number;
  status?: string;
  taskId?: number;
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

type UserRoleInput = {
  id: number;
  role: UserRole;
};

type UserStatusInput = {
  id: number;
  status: UserStatus;
};

export type ApiContract = {
  login: ApiOperation<LoginRequest, AuthResponse>;
  register: ApiOperation<RegisterRequest, AuthResponse>;
  getCurrentUser: ApiOperation<undefined, CurrentUser>;
  logout: ApiOperation<{ refreshToken?: string } | undefined, void>;
  fetchDashboardOverview: ApiOperation<{ llmTrendDays: number }, DashboardOverview>;
  fetchReviews: ApiOperation<ReviewQuery, PageResponse<ReviewTask>>;
  fetchReviewDetail: ApiOperation<{ id: number }, ReviewTaskDetail>;
  fetchReviewFindings: ApiOperation<ReviewFindingsPageInput, PageResponse<ReviewFinding>>;
  fetchReviewChangedFiles: ApiOperation<ReviewChangedFilesPageInput, PageResponse<ChangedFile>>;
  fetchReviewMissingTests: ApiOperation<ReviewDetailPageInput, PageResponse<MissingTest>>;
  fetchReviewStatus: ApiOperation<{ id: number }, ReviewTaskStatus>;
  fetchGithubCommentPreview: ApiOperation<{ id: number }, GithubCommentPreview>;
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
  fetchReviewRules: ApiOperation<undefined, ReviewRulesResponse>;
  createReviewRule: ApiOperation<ReviewRuleConfigRequest, ReviewRuleConfig>;
  updateReviewRule: ApiOperation<{ id: string; payload: ReviewRuleConfigRequest }, ReviewRuleConfig>;
  updateReviewRuleStatus: ApiOperation<{ id: string; payload: ReviewRuleStatusRequest }, ReviewRuleConfig>;
  testGithubIntegrationConnection: ApiOperation<GithubIntegrationConfigRequest | undefined, ConnectionTestResult>;
  testMysqlConnection: ApiOperation<ServiceIntegrationConfigRequest | undefined, ConnectionTestResult>;
  testRabbitMqConnection: ApiOperation<ServiceIntegrationConfigRequest | undefined, ConnectionTestResult>;
  testReviewPolicyConnection: ApiOperation<ReviewPolicyConfigRequest | undefined, ConnectionTestResult>;
  fetchNotificationBindings: ApiOperation<undefined, PageResponse<NotificationBinding>>;
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
  fetchUsers: ApiOperation<undefined, ManagedUser[]>;
  fetchUserOperationAudits: ApiOperation<undefined, UserOperationAudit[]>;
  createUser: ApiOperation<UserCreateRequest, ManagedUser>;
  updateUserRole: ApiOperation<UserRoleInput, ManagedUser>;
  updateUserStatus: ApiOperation<UserStatusInput, ManagedUser>;
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
  logout: {
    method: "POST",
    path: () => "/api/v1/auth/logout",
    body: input => input ?? {}
  },
  fetchDashboardOverview: {
    path: () => "/api/v1/dashboard/overview",
    query: input => ({ llmTrendDays: input.llmTrendDays })
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
      keyword: input.keyword
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
  fetchReviewStatus: {
    path: input => `/api/v1/reviews/${idSegment(input.id)}/status`
  },
  fetchGithubCommentPreview: {
    path: input => `/api/v1/reviews/${idSegment(input.id)}/github-comments/preview`
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
    query: () => ({ page: 1, pageSize: 100 })
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
    path: () => "/api/v1/users"
  },
  fetchUserOperationAudits: {
    path: () => "/api/v1/users/audits"
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
  }
};

export const apiRequest = async <Operation extends keyof ApiContract>(
  operation: Operation,
  input: ApiContract[Operation]["input"]
): Promise<ApiContract[Operation]["response"]> => {
  const endpoint = apiEndpoints[operation] as ApiEndpoint<ApiContract[Operation]["input"]>;
  const options: RequestInit = {};
  const method = endpoint.method ?? "GET";
  if (method !== "GET") {
    options.method = method;
  }
  const payload = endpoint.body?.(input);
  if (payload !== undefined) {
    options.body = JSON.stringify(payload);
  }
  return request<ApiContract[Operation]["response"]>(endpoint.path(input), endpoint.query?.(input), options);
};
