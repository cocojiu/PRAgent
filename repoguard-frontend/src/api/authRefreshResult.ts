import type { ApiResponse } from "@/api/apiEnvelope";
import { RequestError } from "@/utils/errors";
import type { RequestErrorOptions } from "@/utils/errors";
import { commonUserMessages } from "@/utils/userMessages";

export interface TokenPairResponse {
  accessToken: string;
}

export type AuthRefreshResult = {
  ok: boolean;
  body?: ApiResponse<TokenPairResponse>;
  status?: number;
  error?: RequestError;
};

export type AuthRefreshOutcome =
  | { kind: "success" }
  | { kind: "invalid-session" }
  | { kind: "transient-failure" | "rejected"; error: RequestError };

const preservedCodes = new Set([
  "NETWORK_ERROR", "REQUEST_TIMEOUT", "REQUEST_ABORTED", "INVALID_API_RESPONSE", "TOO_MANY_REQUESTS"
]);

export const refreshFailure = (
  kind: "transient-failure" | "rejected",
  options: RequestErrorOptions = {}
): Extract<AuthRefreshOutcome, { error: RequestError }> => {
  const code = options.code && preservedCodes.has(options.code)
    ? options.code
    : kind === "rejected" ? "AUTH_REFRESH_REJECTED" : "AUTH_REFRESH_UNAVAILABLE";
  const message = code === "REQUEST_TIMEOUT" ? commonUserMessages.requestTimeout
    : code === "REQUEST_ABORTED" ? commonUserMessages.requestAborted
    : code === "NETWORK_ERROR" ? commonUserMessages.networkError
    : code === "INVALID_API_RESPONSE" ? commonUserMessages.invalidResponse
    : code === "TOO_MANY_REQUESTS" ? commonUserMessages.tooManyRequests
    : kind === "rejected" ? commonUserMessages.authRefreshRejected : commonUserMessages.authRefreshUnavailable;
  return { kind, error: new RequestError(message, { ...options, code }) };
};

export const resolveRefreshOutcome = (response: AuthRefreshResult): AuthRefreshOutcome => {
  const token = response.ok && response.body?.success ? response.body.data?.accessToken : undefined;
  if (typeof token === "string" && token.trim().length > 0) {
    return { kind: "success" };
  }
  if (!response.ok && response.status === 401) {
    return { kind: "invalid-session" };
  }
  const status = response.error?.status ?? response.status ?? 0;
  const rejected = response.error?.code === "REQUEST_ABORTED"
    || status >= 400 && status < 500 && ![408, 429].includes(status);
  return refreshFailure(rejected ? "rejected" : "transient-failure", {
    status,
    code: response.ok ? "INVALID_API_RESPONSE" : status === 429 ? "TOO_MANY_REQUESTS" : response.error?.code,
    timestamp: response.error?.timestamp,
    errorId: response.error?.errorId
  });
};
