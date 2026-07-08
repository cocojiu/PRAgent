import { normalizeRequestError, unwrapResponse } from "@/api/apiEnvelope";
import type { ApiResponse } from "@/api/apiEnvelope";
import { AuthSessionRefreshCoordinator } from "@/api/authRefreshCoordinator";
import type { AuthRefreshResult, TokenPairResponse } from "@/api/authRefreshCoordinator";
import {
  clearAuthToken,
  hasAuthToken,
  resolveCsrfToken,
  resolveAccessToken,
  resolveRefreshToken,
  saveAuthToken,
  saveAuthTokens
} from "@/api/authSession";

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "";
const AUTH_FETCH_CREDENTIALS: RequestCredentials = "include";
const TRACE_ID_HEADER = "X-Trace-Id";
type InternalRequestInit = RequestInit & { skipAuthorization?: boolean };

export type RequestWithMetaResult<T> = {
  data: T;
  traceId: string;
  responseBytes: number;
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
  options: RequestInit = {}
): Promise<T> => {
  const result = await requestWithMeta<T>(path, params, options);
  return result.data;
};

export const requestWithMeta = async <T>(
  path: string,
  params?: Record<string, string | number | undefined>,
  options: RequestInit = {}
): Promise<RequestWithMetaResult<T>> => {
  const response = await safeDoRequest(path, params, options);
  if (response.ok || response.status !== 401 || isRefreshExcludedAuthPath(path)) {
    return unwrapResponseWithMeta<T>(response);
  }

  const refreshed = await refreshCoordinator.refreshSession();
  if (!refreshed) {
    clearAuthToken();
    redirectToLogin();
    return unwrapResponseWithMeta<T>(response);
  }
  return unwrapResponseWithMeta<T>(await safeDoRequest(path, params, options));
};

const doRequest = async (
  path: string,
  params?: Record<string, string | number | undefined>,
  options: InternalRequestInit = {}
) => {
  const { skipAuthorization, ...fetchOptions } = options;
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

  return fetch(buildUrl(path, params), {
    ...fetchOptions,
    headers,
    credentials: fetchOptions.credentials ?? AUTH_FETCH_CREDENTIALS
  }) as Promise<Response>;
};

const unwrapResponseWithMeta = async <T>(response: Response): Promise<RequestWithMetaResult<T>> => {
  const responseBytes = await responseSizeBytes(response);
  return {
    data: await unwrapResponse<T>(response),
    traceId: response.headers.get(TRACE_ID_HEADER) || "",
    responseBytes,
    status: response.status
  };
};

const responseSizeBytes = async (response: Response) => {
  const contentLength = response.headers.get("Content-Length");
  if (contentLength && /^\d+$/.test(contentLength)) {
    return Number(contentLength);
  }
  try {
    const text = await response.clone().text();
    return new TextEncoder().encode(text).length;
  } catch {
    return 0;
  }
};

const safeDoRequest = async (
  path: string,
  params?: Record<string, string | number | undefined>,
  options: InternalRequestInit = {}
) => {
  try {
    return await doRequest(path, params, options);
  } catch (error) {
    throw normalizeRequestError(error);
  }
};

async function requestAuthRefreshSession(): Promise<AuthRefreshResult> {
  const response = await safeDoRequest("/api/v1/auth/refresh", undefined, {
    method: "POST",
    skipAuthorization: true
  });
  if (!response.ok) {
    return { ok: false };
  }
  return {
    ok: true,
    body: await response.json() as ApiResponse<TokenPairResponse>
  };
}

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
  return `${Date.now().toString(16)}-${Math.random().toString(16).slice(2, 10)}`;
};
