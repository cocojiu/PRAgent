interface ApiResponse<T> {
  success: boolean;
  code: string;
  message: string;
  data: T;
  timestamp: string;
}

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "";

const buildUrl = (path: string, params?: Record<string, string | number | undefined>) => {
  const url = new URL(`${API_BASE_URL}${path}`, window.location.origin);
  Object.entries(params ?? {}).forEach(([key, value]) => {
    if (value !== undefined && value !== "") {
      url.searchParams.set(key, String(value));
    }
  });
  return url.toString();
};

export const request = async <T>(path: string, params?: Record<string, string | number | undefined>): Promise<T> => {
  const response = await fetch(buildUrl(path, params));
  if (!response.ok) {
    throw new Error(`请求失败：HTTP ${response.status}`);
  }

  const body = (await response.json()) as ApiResponse<T>;
  if (!body.success) {
    throw new Error(body.message || body.code || "请求失败");
  }
  return body.data;
};
