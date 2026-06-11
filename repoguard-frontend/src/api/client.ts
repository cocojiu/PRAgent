interface ApiResponse<T> {
  success: boolean;
  code: string;
  message: string;
  data: T;
  timestamp: string;
}

interface TokenPairResponse {
  accessToken: string;
  refreshToken: string;
}

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "";
const ADMIN_KEY_HEADER = import.meta.env.VITE_REPOGUARD_ADMIN_API_KEY_HEADER ?? "X-RepoGuard-Admin-Key";
const ADMIN_KEY_STORAGE_KEYS = [
  "repoguard.adminApiKey",
  "REPOGUARD_ADMIN_API_KEY"
];
const ACCESS_TOKEN_STORAGE_KEY = "repoguard.accessToken";
const REFRESH_TOKEN_STORAGE_KEY = "repoguard.refreshToken";
const LEGACY_AUTH_TOKEN_STORAGE_KEY = "repoguard.authToken";

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
  const response = await doRequest(path, params, options);
  if (response.ok || response.status !== 401 || isAuthPath(path)) {
    return unwrapResponse(response);
  }

  const refreshed = await refreshSession();
  if (!refreshed) {
    clearAuthToken();
    redirectToLogin();
    return unwrapResponse(response);
  }
  return unwrapResponse(await doRequest(path, params, options));
};

export const saveAuthTokens = (accessToken: string, refreshToken: string, remember: boolean) => {
  clearAuthToken();
  const storage = remember ? window.localStorage : window.sessionStorage;
  storage.setItem(ACCESS_TOKEN_STORAGE_KEY, accessToken);
  storage.setItem(REFRESH_TOKEN_STORAGE_KEY, refreshToken);
};

export const saveAuthToken = (token: string, remember: boolean) => {
  saveAuthTokens(token, "", remember);
};

export const clearAuthToken = () => {
  window.sessionStorage.removeItem(ACCESS_TOKEN_STORAGE_KEY);
  window.sessionStorage.removeItem(REFRESH_TOKEN_STORAGE_KEY);
  window.sessionStorage.removeItem(LEGACY_AUTH_TOKEN_STORAGE_KEY);
  window.localStorage.removeItem(ACCESS_TOKEN_STORAGE_KEY);
  window.localStorage.removeItem(REFRESH_TOKEN_STORAGE_KEY);
  window.localStorage.removeItem(LEGACY_AUTH_TOKEN_STORAGE_KEY);
};

export const hasAuthToken = () => Boolean(resolveAccessToken() || resolveRefreshToken());

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
  const adminApiKey = resolveAdminApiKey();
  if (adminApiKey && !headers.has(ADMIN_KEY_HEADER)) {
    headers.set(ADMIN_KEY_HEADER, adminApiKey);
  }
  const authToken = resolveAccessToken();
  if (authToken && !headers.has("Authorization")) {
    headers.set("Authorization", `Bearer ${authToken}`);
  }

  return fetch(buildUrl(path, params), {
    ...options,
    headers
  }) as Promise<Response>;
};

const unwrapResponse = async <T>(response: Response): Promise<T> => {
  let body: ApiResponse<T> | undefined;
  try {
    body = (await response.json()) as ApiResponse<T>;
  } catch {
    body = undefined;
  }

  if (!response.ok) {
    throw new Error(body?.message || body?.code || `请求失败：HTTP ${response.status}`);
  }
  if (!body?.success) {
    throw new Error(body?.message || body?.code || "请求失败");
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
  if (!refreshToken) {
    return false;
  }
  const remember = isRefreshTokenRemembered();
  const response = await fetch(buildUrl("/api/v1/auth/refresh"), {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ refreshToken })
  });
  if (!response.ok) {
    return false;
  }
  const body = (await response.json()) as ApiResponse<TokenPairResponse>;
  if (!body.success || !body.data?.accessToken || !body.data?.refreshToken) {
    return false;
  }
  saveAuthTokens(body.data.accessToken, body.data.refreshToken, remember);
  return true;
};

const redirectToLogin = () => {
  if (window.location.pathname === "/login") {
    return;
  }
  const redirect = encodeURIComponent(`${window.location.pathname}${window.location.search}`);
  window.location.assign(`/login?redirect=${redirect}`);
};

const isAuthPath = (path: string) => path.startsWith("/api/v1/auth/");

const isRefreshTokenRemembered = () => Boolean(window.localStorage.getItem(REFRESH_TOKEN_STORAGE_KEY));

const resolveAdminApiKey = () => {
  const envKey = import.meta.env.VITE_REPOGUARD_ADMIN_API_KEY;
  if (envKey) {
    return envKey;
  }
  if (typeof window === "undefined") {
    return "";
  }
  for (const key of ADMIN_KEY_STORAGE_KEYS) {
    const sessionValue = window.sessionStorage.getItem(key);
    if (sessionValue) {
      return sessionValue;
    }
    const localValue = window.localStorage.getItem(key);
    if (localValue) {
      return localValue;
    }
  }
  return "";
};

const resolveAccessToken = () => {
  if (typeof window === "undefined") {
    return "";
  }
  return window.sessionStorage.getItem(ACCESS_TOKEN_STORAGE_KEY)
    || window.localStorage.getItem(ACCESS_TOKEN_STORAGE_KEY)
    || window.sessionStorage.getItem(LEGACY_AUTH_TOKEN_STORAGE_KEY)
    || window.localStorage.getItem(LEGACY_AUTH_TOKEN_STORAGE_KEY)
    || "";
};
