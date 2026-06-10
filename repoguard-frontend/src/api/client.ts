interface ApiResponse<T> {
  success: boolean;
  code: string;
  message: string;
  data: T;
  timestamp: string;
}

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "";
const ADMIN_KEY_HEADER = import.meta.env.VITE_REPOGUARD_ADMIN_API_KEY_HEADER ?? "X-RepoGuard-Admin-Key";
const ADMIN_KEY_STORAGE_KEYS = [
  "repoguard.adminApiKey",
  "REPOGUARD_ADMIN_API_KEY"
];
const AUTH_TOKEN_STORAGE_KEY = "repoguard.authToken";

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
  const headers = new Headers(options.headers);
  if (options.body && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  const adminApiKey = resolveAdminApiKey();
  if (adminApiKey && !headers.has(ADMIN_KEY_HEADER)) {
    headers.set(ADMIN_KEY_HEADER, adminApiKey);
  }
  const authToken = resolveAuthToken();
  if (authToken && !headers.has("Authorization")) {
    headers.set("Authorization", `Bearer ${authToken}`);
  }

  const response = await fetch(buildUrl(path, params), {
    ...options,
    headers
  });

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

export const saveAuthToken = (token: string, remember: boolean) => {
  window.sessionStorage.removeItem(AUTH_TOKEN_STORAGE_KEY);
  window.localStorage.removeItem(AUTH_TOKEN_STORAGE_KEY);
  const storage = remember ? window.localStorage : window.sessionStorage;
  storage.setItem(AUTH_TOKEN_STORAGE_KEY, token);
};

export const clearAuthToken = () => {
  window.sessionStorage.removeItem(AUTH_TOKEN_STORAGE_KEY);
  window.localStorage.removeItem(AUTH_TOKEN_STORAGE_KEY);
};

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

const resolveAuthToken = () => {
  if (typeof window === "undefined") {
    return "";
  }
  return window.sessionStorage.getItem(AUTH_TOKEN_STORAGE_KEY)
    || window.localStorage.getItem(AUTH_TOKEN_STORAGE_KEY)
    || "";
};
