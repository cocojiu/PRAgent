export type IntegrationStatus = "connected" | "missing_secret" | "failed";
export type SecretStatus = "missing" | "configured" | "key_mismatch" | "decrypt_failed";

export interface IntegrationField {
  label: string;
  value: string;
  type: "text" | "password" | "select";
  placeholder?: string;
  options?: string[];
}

export interface IntegrationConfig {
  id: string;
  name: string;
  description: string;
  status: IntegrationStatus;
  statusText: string;
  message: string;
  metaLabel: string;
  metaValue: string;
  fields: IntegrationField[];
  diagnostics?: IntegrationDiagnosticItem[];
}

export interface IntegrationDiagnosticItem {
  label: string;
  value: string;
  status?: "success" | "warning" | "danger" | "info";
}

export interface GithubIntegrationConfig {
  provider: string;
  status: "configured" | "not_configured" | "failed";
  baseUrl: string;
  token?: string;
  secretStatus?: SecretStatus;
  defaultOwner?: string;
  defaultRepo?: string;
  lastCheckedAt?: string;
  lastError?: string;
  updatedAt?: string;
}

export interface GithubIntegrationConfigRequest {
  baseUrl: string;
  token?: string;
  defaultOwner?: string;
  defaultRepo?: string;
}

export interface GithubChecksDiagnostic {
  code: string;
  label: string;
  status: string;
  message: string;
  blocking: boolean;
}

export interface GithubChecksWebhookStatus {
  endpointUrl: string;
  enabled: boolean;
  signatureRequired: boolean;
  secretConfigured: boolean;
  repositoriesRestricted: boolean;
  branchesRestricted: boolean;
  lastDeliveryId?: string;
  lastDeliveryStatus: string;
  lastDeliveryAt?: string;
}

export interface GithubChecksPreview {
  attempted: boolean;
  created: boolean;
  headSha?: string;
  externalId?: string;
  remoteCheckRunId?: number;
  desiredStage: string;
  desiredVersion: number;
  appliedStage?: string;
  appliedVersion: number;
  retryAttempts: number;
  annotationCount: number;
  annotationTruncated: boolean;
  status: string;
  conclusion?: string;
  message: string;
}

export interface GithubChecksSetupStatus {
  organization: string;
  repository: string;
  appEnabled: boolean;
  appConfigured: boolean;
  installationId?: number;
  installationAllowlisted: boolean;
  repositoryAuthorized: boolean;
  metadataPermission: boolean;
  contentsPermission: boolean;
  pullRequestsPermission: boolean;
  checksPermission: boolean;
  globalCheckRunEnabled: boolean;
  repositoryCheckRunEnabled: boolean;
  effectiveCheckRunEnabled: boolean;
  policyVersion: number;
  webhook: GithubChecksWebhookStatus;
  diagnostics: GithubChecksDiagnostic[];
  preview: GithubChecksPreview;
  ready: boolean;
  mergeGateGuidance: string;
}

export interface GithubChecksPreviewRequest {
  organization: string;
  repository: string;
  pullRequestNumber: number;
}

export interface GithubChecksPolicyRequest {
  organization: string;
  repository: string;
  enabled: boolean;
  expectedVersion: number;
  confirmed: boolean;
}

export interface ServiceIntegrationConfig {
  provider: string;
  status: "configured" | "not_configured" | "failed";
  baseUrl: string;
  username?: string;
  secret?: string;
  secretStatus?: SecretStatus;
  resource?: string;
  lastCheckedAt?: string;
  lastError?: string;
  updatedAt?: string;
}

export interface ServiceIntegrationConfigRequest {
  baseUrl: string;
  username?: string;
  secret?: string;
  resource?: string;
}

export interface ReviewPolicyConfig {
  llmEnabled: boolean;
  llmProvider: string;
  modelName: string;
  baseUrl?: string;
  apiKey?: string;
  secretStatus?: SecretStatus;
  timeoutSeconds: number;
  temperature: number;
  maxTokens: number;
  fallbackToRules: boolean;
  workerConcurrency: number;
  chunkFileThreshold: number;
  chunkLineThreshold: number;
  chunkMaxFiles: number;
  chunkMaxLines: number;
  inputTokenPricePerMillion: number;
  outputTokenPricePerMillion: number;
  updatedAt?: string;
}

export type ReviewPolicyConfigRequest = ReviewPolicyConfig;

export interface ConnectionTestResult {
  success: boolean;
  status: "connected" | "failed";
  message: string;
  checkedAt: string;
  testedConfigSource?: "submitted_config" | "saved_config" | "runtime_config" | string;
  runtimeHealthy?: boolean | null;
  savedConfigHealthy?: boolean | null;
  mismatch?: boolean | null;
  runtimeConnectionStatus?: string | null;
  savedConfigStatus?: string | null;
}
