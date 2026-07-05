import { normalizeRequestError, unwrapResponse } from "@/api/apiEnvelope";
import { AuthSessionRefreshCoordinator } from "@/api/authRefreshCoordinator";
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

const refreshCoordinator = new AuthSessionRefreshCoordinator(
  path => buildUrl(path),
  AUTH_FETCH_CREDENTIALS
);

export const request = async <T>(
  path: string,
  params?: Record<string, string | number | undefined>,
  options: RequestInit = {}
): Promise<T> => {
  const response = await safeDoRequest(path, params, options);
  if (response.ok || response.status !== 401 || isRefreshExcludedAuthPath(path)) {
    return unwrapResponse(response);
  }

  const refreshed = await refreshCoordinator.refreshSession();
  if (!refreshed) {
    clearAuthToken();
    redirectToLogin();
    return unwrapResponse(response);
  }
  return unwrapResponse(await safeDoRequest(path, params, options));
};

const doRequest = async (
  path: string,
  params?: Record<string, string | number | undefined>,
  options: RequestInit = {}
) => {
  const headers = new Headers(options.headers);
  if (options.body && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  const authToken = resolveAccessToken();
  if (authToken && !headers.has("Authorization")) {
    headers.set("Authorization", `Bearer ${authToken}`);
  }
  const method = options.method?.toUpperCase() ?? "GET";
  const csrfToken = resolveCsrfToken();
  if (csrfToken && requiresAuthCsrfHeader(path, method) && !headers.has("X-RepoGuard-CSRF")) {
    headers.set("X-RepoGuard-CSRF", csrfToken);
  }

  return fetch(buildUrl(path, params), {
    ...options,
    headers,
    credentials: options.credentials ?? AUTH_FETCH_CREDENTIALS
  }) as Promise<Response>;
};

const safeDoRequest = async (
  path: string,
  params?: Record<string, string | number | undefined>,
  options: RequestInit = {}
) => {
  try {
    return await doRequest(path, params, options);
  } catch (error) {
    throw normalizeRequestError(error);
  }
};

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
