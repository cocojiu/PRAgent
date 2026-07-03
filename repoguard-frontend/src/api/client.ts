import { RequestError } from "@/utils/errors";

interface ApiResponse<T> {
  success: boolean;
  code: string;
  message: string;
  data: T;
  timestamp: string;
}

interface TokenPairResponse {
  accessToken: string;
  refreshToken?: string;
}

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "";
const ACCESS_TOKEN_STORAGE_KEY = "repoguard.accessToken";
const REFRESH_TOKEN_STORAGE_KEY = "repoguard.refreshToken";
const LEGACY_AUTH_TOKEN_STORAGE_KEY = "repoguard.authToken";
const SESSION_MARKER_STORAGE_KEY = "repoguard.session";
const SESSION_MARKER_VALUE = "active";
const AUTH_FETCH_CREDENTIALS: RequestCredentials = "include";

let activeAccessToken = "";
let refreshPromise: Promise<boolean> | undefined;

const buildUrl = (path: string, params?: Record<string, string | number | undefined>) => {
  const url = new URL(`${API_BASE_URL}${path}`, window.location.origin);
  Object.entries(params ?? {}).forEach(([key, value]) => {
    if (value !== undefined && value !== "") {
      url.searchParams.set(key, String(value));
    }
  });
  return url.toString();
};

export const request = async <T>(
  path: string,
  params?: Record<string, string | number | undefined>,
  options: RequestInit = {}
): Promise<T> => {
  const response = await safeDoRequest(path, params, options);
  if (response.ok || response.status !== 401 || isRefreshExcludedAuthPath(path)) {
    return unwrapResponse(response);
  }

  const refreshed = await refreshSession();
  if (!refreshed) {
    clearAuthToken();
    redirectToLogin();
    return unwrapResponse(response);
  }
  return unwrapResponse(await safeDoRequest(path, params, options));
};

export const saveAuthTokens = (accessToken: string, _refreshToken: string, remember: boolean) => {
  clearAuthToken();
  activeAccessToken = accessToken;
  saveSessionMarker(remember);
};

export const saveAuthToken = (token: string, remember: boolean) => {
  saveAuthTokens(token, "", remember);
};

export const clearAuthToken = () => {
  activeAccessToken = "";
  window.sessionStorage.removeItem(ACCESS_TOKEN_STORAGE_KEY);
  window.sessionStorage.removeItem(REFRESH_TOKEN_STORAGE_KEY);
  window.sessionStorage.removeItem(LEGACY_AUTH_TOKEN_STORAGE_KEY);
  window.localStorage.removeItem(ACCESS_TOKEN_STORAGE_KEY);
  window.localStorage.removeItem(REFRESH_TOKEN_STORAGE_KEY);
  window.localStorage.removeItem(LEGACY_AUTH_TOKEN_STORAGE_KEY);
  window.sessionStorage.removeItem(SESSION_MARKER_STORAGE_KEY);
  window.localStorage.removeItem(SESSION_MARKER_STORAGE_KEY);
};

export const hasAuthToken = () => Boolean(resolveAccessToken() || hasSessionMarker() || resolveRefreshToken());

export const resolveRefreshToken = () => {
  if (typeof window === "undefined") {
    return "";
  }
  return window.sessionStorage.getItem(REFRESH_TOKEN_STORAGE_KEY)
    || window.localStorage.getItem(REFRESH_TOKEN_STORAGE_KEY)
    || "";
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
    if (error instanceof RequestError) {
      throw error;
    }
    throw new RequestError("Network request failed. Please check your connection and retry.", {
      status: 0,
      code: "NETWORK_ERROR"
    });
  }
};

const unwrapResponse = async <T>(response: Response): Promise<T> => {
  let body: ApiResponse<T> | undefined;
  try {
    body = (await response.json()) as ApiResponse<T>;
  } catch {
    body = undefined;
  }

  if (!response.ok) {
    throw new RequestError(body?.message || body?.code || `HTTP ${response.status}`, {
      status: response.status,
      code: body?.code,
      timestamp: body?.timestamp
    });
  }
  if (!body?.success) {
    throw new RequestError(body?.message || body?.code || "Request failed", {
      status: response.status,
      code: body?.code,
      timestamp: body?.timestamp
    });
  }
  return body.data;
};

const refreshSession = async () => {
  if (!refreshPromise) {
    refreshPromise = doRefreshSession().finally(() => {
      refreshPromise = undefined;
    });
  }
  return refreshPromise;
};

const doRefreshSession = async () => {
  const refreshToken = resolveRefreshToken();
  if (!refreshToken && !hasSessionMarker()) {
    return false;
  }
  const remember = isSessionRemembered();
  const headers = new Headers();
  let requestBody: string | undefined;
  if (refreshToken) {
    headers.set("Content-Type", "application/json");
    requestBody = JSON.stringify({ refreshToken });
  }
  const response = await fetch(buildUrl("/api/v1/auth/refresh"), {
    method: "POST",
    headers,
    body: requestBody,
    credentials: AUTH_FETCH_CREDENTIALS
  });
  if (!response.ok) {
    return false;
  }
  const body = (await response.json()) as ApiResponse<TokenPairResponse>;
  if (!body.success || !body.data?.accessToken) {
    return false;
  }
  saveAuthTokens(body.data.accessToken, body.data.refreshToken ?? "", remember);
  return true;
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

const isSessionRemembered = () => Boolean(
  window.localStorage.getItem(SESSION_MARKER_STORAGE_KEY)
    || window.localStorage.getItem(REFRESH_TOKEN_STORAGE_KEY)
);

const hasSessionMarker = () => Boolean(
  window.sessionStorage.getItem(SESSION_MARKER_STORAGE_KEY)
    || window.localStorage.getItem(SESSION_MARKER_STORAGE_KEY)
);

const saveSessionMarker = (remember: boolean) => {
  const storage = remember ? window.localStorage : window.sessionStorage;
  storage.setItem(SESSION_MARKER_STORAGE_KEY, SESSION_MARKER_VALUE);
};

const resolveAccessToken = () => {
  if (typeof window === "undefined") {
    return "";
  }
  if (activeAccessToken) {
    return activeAccessToken;
  }
  activeAccessToken = consumeStoredAccessToken();
  return activeAccessToken;
};

const consumeStoredAccessToken = () => {
  const token = window.sessionStorage.getItem(ACCESS_TOKEN_STORAGE_KEY)
    || window.localStorage.getItem(ACCESS_TOKEN_STORAGE_KEY)
    || window.sessionStorage.getItem(LEGACY_AUTH_TOKEN_STORAGE_KEY)
    || window.localStorage.getItem(LEGACY_AUTH_TOKEN_STORAGE_KEY)
    || "";
  if (token) {
    window.sessionStorage.removeItem(ACCESS_TOKEN_STORAGE_KEY);
    window.localStorage.removeItem(ACCESS_TOKEN_STORAGE_KEY);
    window.sessionStorage.removeItem(LEGACY_AUTH_TOKEN_STORAGE_KEY);
    window.localStorage.removeItem(LEGACY_AUTH_TOKEN_STORAGE_KEY);
  }
  return token;
};
