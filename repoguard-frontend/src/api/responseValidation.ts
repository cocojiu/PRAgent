import type { AuthResponse, AuthUser, CurrentUser } from "@/api/auth";
import type { ReviewTaskSummary } from "@/api/generated/reviewDetailTypes";
import type {
  GithubIntegrationConfig,
  GithubChecksSetupStatus,
  PageResponse,
  ReviewPolicyConfig,
  SecretReEncryptionItem,
  SecretReEncryptionJob,
  ServiceIntegrationConfig
} from "@/types";
import { RequestError } from "@/utils/errors";

export type ApiResponseValidator<T> = (value: unknown) => value is T;

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);

const hasString = (value: Record<string, unknown>, key: string) => typeof value[key] === "string";
const hasNumber = (value: Record<string, unknown>, key: string) =>
  typeof value[key] === "number" && Number.isFinite(value[key]);
const hasBoolean = (value: Record<string, unknown>, key: string) => typeof value[key] === "boolean";

const isAuthUser = (value: unknown): value is AuthUser =>
  isRecord(value)
  && hasNumber(value, "id")
  && hasString(value, "username")
  && hasString(value, "email")
  && hasString(value, "role");

export const isAuthResponse: ApiResponseValidator<AuthResponse> = (value): value is AuthResponse =>
  isRecord(value)
  && hasString(value, "accessToken")
  && hasString(value, "tokenType")
  && hasNumber(value, "accessTokenExpiresInSeconds")
  && hasNumber(value, "refreshTokenExpiresInSeconds")
  && isAuthUser(value.user);

export const isCurrentUser: ApiResponseValidator<CurrentUser> = (value): value is CurrentUser =>
  isAuthUser(value) && typeof (value as Partial<CurrentUser>).status === "string";

export const isReviewTaskSummary: ApiResponseValidator<ReviewTaskSummary> = (value): value is ReviewTaskSummary =>
  isRecord(value)
  && hasNumber(value, "id")
  && hasString(value, "status")
  && hasString(value, "title")
  && hasString(value, "repository")
  && hasString(value, "riskLevel")
  && Array.isArray(value.findings)
  && Array.isArray(value.missingTests)
  && Array.isArray(value.changedFiles)
  && Array.isArray(value.timeline);

const hasIntegrationCore = (value: unknown): value is Record<string, unknown> =>
  isRecord(value)
  && hasString(value, "provider")
  && hasString(value, "status")
  && hasString(value, "baseUrl");

export const isGithubIntegrationConfig: ApiResponseValidator<GithubIntegrationConfig> =
  (value): value is GithubIntegrationConfig => hasIntegrationCore(value);

const isGithubChecksDiagnostic = (value: unknown): boolean =>
  isRecord(value)
  && hasString(value, "code")
  && hasString(value, "label")
  && hasString(value, "status")
  && hasString(value, "message")
  && hasBoolean(value, "blocking");

const isGithubChecksPreview = (value: unknown): boolean =>
  isRecord(value)
  && hasBoolean(value, "attempted")
  && hasBoolean(value, "created")
  && hasString(value, "desiredStage")
  && hasNumber(value, "desiredVersion")
  && hasNumber(value, "appliedVersion")
  && hasNumber(value, "retryAttempts")
  && hasNumber(value, "annotationCount")
  && hasBoolean(value, "annotationTruncated")
  && hasString(value, "status")
  && hasString(value, "message");

export const isGithubChecksSetupStatus: ApiResponseValidator<GithubChecksSetupStatus> =
  (value): value is GithubChecksSetupStatus =>
    isRecord(value)
    && hasString(value, "organization")
    && hasString(value, "repository")
    && hasBoolean(value, "appEnabled")
    && hasBoolean(value, "appConfigured")
    && hasBoolean(value, "installationAllowlisted")
    && hasBoolean(value, "repositoryAuthorized")
    && hasBoolean(value, "metadataPermission")
    && hasBoolean(value, "contentsPermission")
    && hasBoolean(value, "pullRequestsPermission")
    && hasBoolean(value, "checksPermission")
    && hasBoolean(value, "globalCheckRunEnabled")
    && hasBoolean(value, "repositoryCheckRunEnabled")
    && hasBoolean(value, "effectiveCheckRunEnabled")
    && hasNumber(value, "policyVersion")
    && hasBoolean(value, "ready")
    && isRecord(value.webhook)
    && Array.isArray(value.diagnostics)
    && value.diagnostics.every(isGithubChecksDiagnostic)
    && isGithubChecksPreview(value.preview);

export const isServiceIntegrationConfig: ApiResponseValidator<ServiceIntegrationConfig> =
  (value): value is ServiceIntegrationConfig => hasIntegrationCore(value);

export const isReviewPolicyConfig: ApiResponseValidator<ReviewPolicyConfig> = (value): value is ReviewPolicyConfig =>
  isRecord(value)
  && hasBoolean(value, "llmEnabled")
  && hasString(value, "llmProvider")
  && hasString(value, "modelName")
  && hasNumber(value, "timeoutSeconds")
  && hasNumber(value, "temperature")
  && hasNumber(value, "maxTokens")
  && hasBoolean(value, "fallbackToRules")
  && hasNumber(value, "workerConcurrency")
  && hasNumber(value, "chunkFileThreshold")
  && hasNumber(value, "chunkLineThreshold")
  && hasNumber(value, "chunkMaxFiles")
  && hasNumber(value, "chunkMaxLines")
  && hasNumber(value, "inputTokenPricePerMillion")
  && hasNumber(value, "outputTokenPricePerMillion");

export const isSecretReEncryptionJob: ApiResponseValidator<SecretReEncryptionJob> =
  (value): value is SecretReEncryptionJob =>
    isRecord(value)
    && hasNumber(value, "id")
    && hasBoolean(value, "executed")
    && hasString(value, "status")
    && hasString(value, "sourceKeyId")
    && hasString(value, "targetKeyId")
    && hasString(value, "currentTable")
    && hasNumber(value, "checkpointId")
    && hasNumber(value, "batchSize")
    && hasNumber(value, "scannedCount")
    && hasNumber(value, "reEncryptedCount")
    && hasNumber(value, "skippedCount")
    && hasNumber(value, "failedCount")
    && hasNumber(value, "retryCount");

export const isSecretReEncryptionItem: ApiResponseValidator<SecretReEncryptionItem> =
  (value): value is SecretReEncryptionItem =>
    isRecord(value)
    && hasString(value, "tableName")
    && hasNumber(value, "recordId")
    && hasString(value, "fieldName")
    && hasString(value, "targetKeyId")
    && hasString(value, "status");

export const isSecretReEncryptionItemPage: ApiResponseValidator<PageResponse<SecretReEncryptionItem>> =
  (value): value is PageResponse<SecretReEncryptionItem> =>
    isRecord(value)
    && hasNumber(value, "total")
    && Array.isArray(value.items)
    && value.items.every(isSecretReEncryptionItem);

export const isSecretReEncryptionJobPage: ApiResponseValidator<PageResponse<SecretReEncryptionJob>> =
  (value): value is PageResponse<SecretReEncryptionJob> =>
    isRecord(value)
    && hasNumber(value, "total")
    && Array.isArray(value.items)
    && value.items.every(isSecretReEncryptionJob);

export const validateApiResponse = <T>(
  operation: string,
  value: unknown,
  validator: ApiResponseValidator<T> | undefined,
  status: number
): T => {
  if (!validator || validator(value)) {
    return value as T;
  }
  throw new RequestError(`服务端响应格式异常（${operation}）`, {
    status,
    code: "INVALID_API_RESPONSE"
  });
};
