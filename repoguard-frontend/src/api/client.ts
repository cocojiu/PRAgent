import { normalizeRequestError, unwrapResponse } from "@/api/apiEnvelope";
import type { ApiResponse } from "@/api/apiEnvelope";
import { AuthSessionRefreshCoordinator } from "@/api/authRefreshCoordinator";
import type { AuthRefreshResult, TokenPairResponse } from "@/api/authRefreshCoordinator";
import { RequestError } from "@/utils/errors";
import {
  clearAuthToken,
  hasAuthToken,
  resolveCsrfToken,
  resolveAccessToken,
  resolveRefreshToken,
  saveAuthToken,
  saveAuthTokens
} from "@/api/authSession";
import { activeTenant } from "@/stores/tenantContext";

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "";
const AUTH_FETCH_CREDENTIALS: RequestCredentials = "include";
const TRACE_ID_HEADER = "X-Trace-Id";
const DEFAULT_REQUEST_TIMEOUT_MS = 20_000;
export type ClientRequestInit = RequestInit & { timeoutMs?: number };
type InternalRequestInit = ClientRequestInit & { skipAuthorization?: boolean };

export type RequestWithMetaResult<T> = {
  data: T;
  traceId: string;
  responseBytes?: number;
  status: number;
};

export {
  clearAuthToken,
  hasAuthToken,
  resolveRefreshToken,
  saveAuthToken,
  saveAuthTokens
};

const buildUrl = (path: string, params?: Record<string, string | number | undefined>) => {
  const url = new URL(`${API_BASE_URL}${path}`, window.location.origin);
  Object.entries(params ?? {}).forEach(([key, value]) => {
    if (value !== undefined && value !== "") {
      url.searchParams.set(key, String(value));
    }
  });
  return url.toString();
};

const refreshCoordinator = new AuthSessionRefreshCoordinator(requestAuthRefreshSession);

export const request = async <T>(
  path: string,
  params?: Record<string, string | number | undefined>,
  options: ClientRequestInit = {}
): Promise<T> => {
  const result = await requestWithMeta<T>(path, params, options);
  return result.data;
};

export const requestWithMeta = async <T>(
  path: string,
  params?: Record<string, string | number | undefined>,
  options: ClientRequestInit = {}
): Promise<RequestWithMetaResult<T>> => {
  const deadline = createRequestDeadline(options);
  const requestOptions: ClientRequestInit = { ...options, signal: deadline.signal };
  try {
    const response = await waitForSignal(doRequest(path, params, requestOptions), deadline.signal);
    if (response.ok || response.status !== 401 || isRefreshExcludedAuthPath(path)) {
      return await waitForSignal(unwrapResponseWithMeta<T>(response), deadline.signal);
    }

    const refreshed = await waitForSignal(refreshCoordinator.refreshSession(), deadline.signal);
    if (!refreshed) {
      clearAuthToken();
      redirectToLogin();
      return await waitForSignal(unwrapResponseWithMeta<T>(response), deadline.signal);
    }
    const retryResponse = await waitForSignal(doRequest(path, params, requestOptions), deadline.signal);
    return await waitForSignal(unwrapResponseWithMeta<T>(retryResponse), deadline.signal);
  } catch (error) {
    throw normalizeDeadlineError(error, deadline);
  } finally {
    deadline.dispose();
  }
};

const doRequest = async (
  path: string,
  params?: Record<string, string | number | undefined>,
  options: InternalRequestInit = {}
) => {
  const { skipAuthorization, timeoutMs: _timeoutMs, ...fetchOptions } = options;
  void _timeoutMs;
  const headers = new Headers(options.headers);
  if (options.body && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  const authToken = resolveAccessToken();
  if (!skipAuthorization && authToken && !headers.has("Authorization")) {
    headers.set("Authorization", `Bearer ${authToken}`);
  }
  const method = options.method?.toUpperCase() ?? "GET";
  const csrfToken = resolveCsrfToken();
  if (csrfToken && requiresAuthCsrfHeader(path, method) && !headers.has("X-RepoGuard-CSRF")) {
    headers.set("X-RepoGuard-CSRF", csrfToken);
  }
  if (!headers.has(TRACE_ID_HEADER)) {
    headers.set(TRACE_ID_HEADER, generateTraceId());
  }
  if (activeTenant.value && !headers.has("X-RepoGuard-Tenant")) {
    headers.set("X-RepoGuard-Tenant", activeTenant.value);
  }

  return fetch(buildUrl(path, params), {
    ...fetchOptions,
    headers,
    credentials: fetchOptions.credentials ?? AUTH_FETCH_CREDENTIALS
  }) as Promise<Response>;
};

const unwrapResponseWithMeta = async <T>(response: Response): Promise<RequestWithMetaResult<T>> => {
  const responseBytes = responseSizeBytes(response);
  return {
    data: await unwrapResponse<T>(response),
    traceId: response.headers.get(TRACE_ID_HEADER) || "",
    responseBytes,
    status: response.status
  };
};

const responseSizeBytes = (response: Response): number | undefined => {
  const contentLength = response.headers.get("Content-Length");
  if (contentLength && /^\d+$/.test(contentLength)) {
    const value = Number(contentLength);
    return Number.isSafeInteger(value) ? value : undefined;
  }
  return undefined;
};

async function requestAuthRefreshSession(): Promise<AuthRefreshResult> {
  const deadline = createRequestDeadline({ timeoutMs: DEFAULT_REQUEST_TIMEOUT_MS });
  try {
    const response = await waitForSignal(doRequest("/api/v1/auth/refresh", undefined, {
      method: "POST",
      skipAuthorization: true,
      signal: deadline.signal
    }), deadline.signal);
    if (!response.ok) {
      return { ok: false };
    }
    return {
      ok: true,
      body: await waitForSignal(
        response.json() as Promise<ApiResponse<TokenPairResponse>>,
        deadline.signal
      )
    };
  } catch {
    return { ok: false };
  } finally {
    deadline.dispose();
  }
}

type RequestDeadline = {
  callerSignal?: AbortSignal;
  dispose: () => void;
  signal: AbortSignal;
  timedOut: () => boolean;
};

const createRequestDeadline = (options: ClientRequestInit): RequestDeadline => {
  const controller = new AbortController();
  const callerSignal = options.signal ?? undefined;
  let timeoutReached = false;
  const configuredTimeout = options.timeoutMs ?? DEFAULT_REQUEST_TIMEOUT_MS;
  const timeoutMs = Number.isFinite(configuredTimeout)
    ? Math.max(1, configuredTimeout)
    : DEFAULT_REQUEST_TIMEOUT_MS;
  const abortFromCaller = () => controller.abort(callerSignal?.reason);
  if (callerSignal?.aborted) {
    abortFromCaller();
  } else {
    callerSignal?.addEventListener("abort", abortFromCaller, { once: true });
  }
  const timeout = setTimeout(() => {
    timeoutReached = true;
    controller.abort(new DOMException("Request deadline exceeded", "TimeoutError"));
  }, timeoutMs);

  return {
    callerSignal,
    signal: controller.signal,
    timedOut: () => timeoutReached,
    dispose: () => {
      clearTimeout(timeout);
      callerSignal?.removeEventListener("abort", abortFromCaller);
    }
  };
};

const waitForSignal = <T>(promise: Promise<T>, signal: AbortSignal) => {
  if (signal.aborted) {
    return Promise.reject(signal.reason ?? new DOMException("Request aborted", "AbortError"));
  }
  return new Promise<T>((resolve, reject) => {
    const abort = () => reject(signal.reason ?? new DOMException("Request aborted", "AbortError"));
    signal.addEventListener("abort", abort, { once: true });
    promise.then(
      value => {
        signal.removeEventListener("abort", abort);
        resolve(value);
      },
      error => {
        signal.removeEventListener("abort", abort);
        reject(error);
      }
    );
  });
};

const normalizeDeadlineError = (error: unknown, deadline: RequestDeadline) => {
  if (error instanceof RequestError) {
    return error;
  }
  if (deadline.timedOut()) {
    return new RequestError("Request timed out", {
      status: 0,
      code: "REQUEST_TIMEOUT"
    });
  }
  if (deadline.callerSignal?.aborted || isAbortError(error)) {
    return new RequestError("Request aborted", {
      status: 0,
      code: "REQUEST_ABORTED"
    });
  }
  return normalizeRequestError(error);
};

const isAbortError = (error: unknown) =>
  error instanceof DOMException && ["AbortError", "TimeoutError"].includes(error.name);

const redirectToLogin = () => {
  if (window.location.pathname === "/login") {
    return;
  }
  const redirect = encodeURIComponent(`${window.location.pathname}${window.location.search}`);
  window.location.assign(`/login?redirect=${redirect}`);
};

const isRefreshExcludedAuthPath = (path: string) => {
  const excludedAuthPaths = [
    "/api/v1/auth/login",
    "/api/v1/auth/register",
    "/api/v1/auth/refresh",
    "/api/v1/auth/refresh-token/reset",
    "/api/v1/auth/logout"
  ];
  return excludedAuthPaths.includes(path);
};

const requiresAuthCsrfHeader = (path: string, method: string) =>
  method === "POST" && [
    "/api/v1/auth/refresh",
    "/api/v1/auth/logout"
  ].includes(path);

let fallbackTraceSequence = 0;

const generateTraceId = () => {
  const cryptoApi = globalThis.crypto;
  if (cryptoApi?.randomUUID) {
    return cryptoApi.randomUUID();
  }
  if (cryptoApi?.getRandomValues) {
    const bytes = new Uint8Array(16);
    cryptoApi.getRandomValues(bytes);
    return Array.from(bytes, (value) => value.toString(16).padStart(2, "0")).join("");
  }
  fallbackTraceSequence = (fallbackTraceSequence + 1) % Number.MAX_SAFE_INTEGER;
  return `${Date.now().toString(16)}-${fallbackTraceSequence.toString(16).padStart(8, "0")}`;
};
