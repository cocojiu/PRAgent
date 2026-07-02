export type RequestErrorOptions = {
  status?: number;
  code?: string;
  timestamp?: string;
};

export class RequestError extends Error {
  readonly status?: number;
  readonly code?: string;
  readonly timestamp?: string;

  constructor(message: string, options: RequestErrorOptions = {}) {
    super(message);
    this.name = "RequestError";
    this.status = options.status;
    this.code = options.code;
    this.timestamp = options.timestamp;
  }
}

export const getErrorMessage = (error: unknown, fallback = "操作失败") => {
  if (error instanceof RequestError) {
    if (error.status === 401) {
      return "登录状态已失效，请重新登录";
    }
    if (error.status === 403) {
      return "当前账号权限不足，无法执行该操作";
    }
    if (error.status === 0 || error.code === "NETWORK_ERROR") {
      return "网络连接异常，请检查后重试";
    }
    return error.message || fallback;
  }
  if (error instanceof Error) {
    return error.message || fallback;
  }
  return fallback;
};

export const getAuthErrorMessage = (error: unknown, fallback = "认证失败") => {
  if (error instanceof RequestError) {
    if (error.status === 0 || error.code === "NETWORK_ERROR") {
      return "网络连接异常，请检查后重试";
    }
    return error.message || fallback;
  }
  if (error instanceof Error) {
    return error.message || fallback;
  }
  return fallback;
};
