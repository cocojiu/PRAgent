import type { AuthResponse, AuthUser, CurrentUser } from "@/api/auth";
import type {
  GithubIntegrationConfig,
  ReviewPolicyConfig,
  ReviewTaskSummary,
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
