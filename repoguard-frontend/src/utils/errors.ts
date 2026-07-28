import { commonUserMessages } from "@/utils/userMessages";

export type RequestErrorOptions = {
  status?: number;
  code?: string;
  timestamp?: string;
  errorId?: string;
};

export class RequestError extends Error {
  readonly status?: number;
  readonly code?: string;
  readonly timestamp?: string;
  readonly errorId?: string;

  constructor(message: string, options: RequestErrorOptions = {}) {
    super(message);
    this.name = "RequestError";
    this.status = options.status;
    this.code = options.code;
    this.timestamp = options.timestamp;
    this.errorId = options.errorId;
  }
}

const requestErrorMessages: Readonly<Record<string, string>> = {
  BAD_REQUEST: commonUserMessages.badRequest,
  CONFLICT: commonUserMessages.conflict,
  FORBIDDEN: commonUserMessages.forbidden,
  INTERNAL_ERROR: commonUserMessages.internalError,
  INVALID_API_RESPONSE: commonUserMessages.invalidResponse,
  NETWORK_ERROR: commonUserMessages.networkError,
  PAYLOAD_TOO_LARGE: commonUserMessages.payloadTooLarge,
  TASK_NOT_FOUND: commonUserMessages.taskNotFound,
  TOO_MANY_REQUESTS: commonUserMessages.tooManyRequests,
  UNAUTHORIZED: commonUserMessages.sessionExpired
};

export const getErrorMessage = (error: unknown, fallback: string = commonUserMessages.actionFailed) => {
  if (error instanceof RequestError) {
    if (error.status === 401) {
      return commonUserMessages.sessionExpired;
    }
    if (error.status === 403) {
      return commonUserMessages.forbidden;
    }
    if (error.status === 0 || error.code === "NETWORK_ERROR") {
      return commonUserMessages.networkError;
    }
    return (error.code && requestErrorMessages[error.code]) || fallback;
  }
  if (error instanceof Error) {
    return error.message || fallback;
  }
  return fallback;
};

export const getAuthErrorMessage = (error: unknown, fallback: string = commonUserMessages.authFailed) => {
  if (error instanceof RequestError) {
    if (error.status === 0 || error.code === "NETWORK_ERROR") {
      return commonUserMessages.networkError;
    }
    return (error.code && requestErrorMessages[error.code]) || fallback;
  }
  if (error instanceof Error) {
    return error.message || fallback;
  }
  return fallback;
};
