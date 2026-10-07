import type { AuthResponse, AuthUser, CurrentUser } from "@/api/auth";
import type { ReviewTaskSummary } from "@/api/generated/reviewDetailTypes";
import type {
  GithubIntegrationConfig,
  GithubFeedbackDiagnostics,
  FeedbackSummary,
  CodeownersRecommendations,
  CodeownersAcceptance,
  ReviewAssignmentOptions,
  ReviewMemberAssignment,
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

export const isGithubFeedbackDiagnostics: ApiResponseValidator<GithubFeedbackDiagnostics> =
  (value): value is GithubFeedbackDiagnostics => isRecord(value) && hasBoolean(value, "enabled")
    && Array.isArray(value.events) && value.events.length <= 100 && value.events.every(event =>
      isRecord(event) && hasNumber(event, "id") && hasNumber(event, "taskId")
      && hasNumber(event, "findingId") && hasNumber(event, "attempts")
      && ["PENDING", "APPLIED", "IGNORED", "FAILED"].includes(String(event.status))
      && ["false_positive", "ignored"].includes(String(event.feedbackStatus)));

export const isFeedbackSummary: ApiResponseValidator<FeedbackSummary> = (value): value is FeedbackSummary =>
  isRecord(value) && hasString(value, "windowStart") && hasString(value, "windowEnd")
  && hasNumber(value, "examinedCount") && hasNumber(value, "excludedOrDuplicateCount")
  && hasNumber(value, "sampleSize") && hasBoolean(value, "truncated")
  && Array.isArray(value.sources) && value.sources.length === 2 && value.sources.every(source =>
    isRecord(source) && ["RULE", "LLM"].includes(String(source.source))
    && ["reviewed", "valid", "falsePositive", "fixed", "ignored"].every(key => hasNumber(source, key))
    && ["INSUFFICIENT_DATA", "COUNTS_ONLY"].includes(String(source.evidenceStatus)))
  && Array.isArray(value.details) && value.details.length <= 20 && value.details.every(row =>
    isRecord(row) && hasNumber(row, "findingId") && hasNumber(row, "taskId") && hasNumber(row, "prNumber")
    && ["source", "status", "actor", "feedbackAt", "repository", "headSha"].every(key => hasString(row, key)));

const positiveId = (value: unknown) => typeof value === "number" && Number.isSafeInteger(value) && value > 0;
const boundedCount = (value: unknown, max: number): value is number =>
  typeof value === "number" && Number.isSafeInteger(value) && value >= 0 && value <= max;
const boundedStrings = (value: unknown, count: number, length: number): value is string[] =>
  Array.isArray(value) && value.length <= count && value.every(item => typeof item === "string" && item.length <= length);
const optionalSha = (value: unknown) => value == null || typeof value === "string" && /^[a-f0-9]{40}$/i.test(value);

export const isCodeownersRecommendations: ApiResponseValidator<CodeownersRecommendations> =
  (value): value is CodeownersRecommendations => {
    if (!isRecord(value) || typeof value.status !== "string" || value.status.length > 64 || !positiveId(value.taskId)
      || !(value.attemptId == null || positiveId(value.attemptId)) || !optionalSha(value.headSha) || !optionalSha(value.baseSha)
      || !(value.sourcePath == null || typeof value.sourcePath === "string" && [".github/CODEOWNERS", "CODEOWNERS", "docs/CODEOWNERS"].includes(value.sourcePath))
      || !Array.isArray(value.candidates) || value.candidates.length > 3 || !value.candidates.every(candidate =>
        isRecord(candidate) && positiveId(candidate.userId) && typeof candidate.username === "string"
        && candidate.username.trim().length > 0 && candidate.username.length <= 128
        && boundedCount(candidate.coveredFiles, 200) && candidate.coveredFiles > 0 && boundedCount(candidate.findingCount, 200000)
        && boundedCount(candidate.riskScore, 1600) && boundedStrings(candidate.externalIdentities, 1000, 255)
        && boundedStrings(candidate.paths, 200, 512) && candidate.paths.length > 0)
      || !Array.isArray(value.basis) || value.basis.length > 200 || !value.basis.every(row =>
        isRecord(row) && typeof row.path === "string" && row.path.length <= 512
        && (row.pattern == null || typeof row.pattern === "string" && row.pattern.length <= 256)
        && boundedCount(row.line, 2000) && boundedStrings(row.owners, 20, 255) && boundedStrings(row.changedFiles, 200, 512) && row.changedFiles.length > 0)
      || !boundedStrings(value.uncoveredPaths, 200, 512) || !boundedStrings(value.unmappedIdentities, 4000, 255)) return false;
    if (new Set(value.candidates.map(candidate => candidate.userId)).size !== value.candidates.length) return false;
    return value.status !== "RECOMMENDATIONS" || value.candidates.length > 0 && positiveId(value.attemptId)
      && typeof value.headSha === "string" && typeof value.baseSha === "string" && typeof value.sourcePath === "string";
  };

export const isCodeownersAcceptance: ApiResponseValidator<CodeownersAcceptance> = (value): value is CodeownersAcceptance =>
  isRecord(value) && positiveId(value.taskId) && positiveId(value.attemptId) && typeof value.headSha === "string"
  && /^[a-f0-9]{40}$/i.test(value.headSha) && typeof value.assignee === "string"
  && value.assignee.trim().length > 0 && value.assignee.length <= 128;

export const isReviewAssignmentOptions: ApiResponseValidator<ReviewAssignmentOptions> =
  (value): value is ReviewAssignmentOptions => isRecord(value) && positiveId(value.taskId) && positiveId(value.attemptId)
  && typeof value.headSha === "string" && /^[a-f0-9]{40}$/i.test(value.headSha)
  && typeof value.assignmentVersion === "string" && /^[a-f0-9]{64}$/.test(value.assignmentVersion)
  && typeof value.hasMore === "boolean" && Array.isArray(value.members) && value.members.length <= 20
  && value.members.every(member => isRecord(member) && positiveId(member.userId) && typeof member.username === "string"
    && member.username.trim().length > 0 && member.username.length <= 128)
  && new Set(value.members.map(member => member.userId)).size === value.members.length;
export const isReviewMemberAssignment: ApiResponseValidator<ReviewMemberAssignment> = isCodeownersAcceptance;
