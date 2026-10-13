import type { ApiEndpointMap } from "@/api/endpoint";
import type {
  ApiOperation
} from "@/api/endpoint";
import {
  generatedEndpoint
} from "@/api/generatedEndpoint";
import {
  isGithubChecksSetupStatus,
  isGithubIntegrationConfig,
  isReviewPolicyConfig,
  isSecretReEncryptionItemPage,
  isSecretReEncryptionJob,
  isSecretReEncryptionJobPage,
  isServiceIntegrationConfig
} from "@/api/responseValidation";
import type {
  ConnectionTestResult,
  GithubChecksPolicyRequest,
  GithubChecksPreviewRequest,
  GithubChecksSetupStatus,
  GithubIntegrationConfig,
  GithubIntegrationConfigRequest,
  PageResponse,
  ReviewPolicyConfig,
  ReviewPolicyConfigRequest,
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
  ServiceIntegrationConfig,
  ServiceIntegrationConfigRequest,
  SystemSettings,
  SystemSettingsRequest,
  RepositoryPolicyPreviewResponse,
  RepositorySuppressionRequest,
  RepositorySuppressionResponse
} from "@/types";

export type ConfigurationApiContract = {
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
};

export const configurationEndpoints: ApiEndpointMap<ConfigurationApiContract> = {
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
  })
};
