import { RequestError } from "@/utils/errors";

const ERROR_ID_HEADER = "X-Error-Id";

export interface ApiResponse<T> {
  success: boolean;
  code: string;
  message: string;
  data: T;
  timestamp: string;
}

export const unwrapResponse = async <T>(response: Response): Promise<T> => {
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
      timestamp: body?.timestamp,
      errorId: response.headers.get(ERROR_ID_HEADER) || undefined
    });
  }
  if (!body?.success) {
    throw new RequestError(body?.message || body?.code || "Request failed", {
      status: response.status,
      code: body?.code,
      timestamp: body?.timestamp,
      errorId: response.headers.get(ERROR_ID_HEADER) || undefined
    });
  }
  return body.data;
};

export const normalizeRequestError = (error: unknown) => {
  if (error instanceof RequestError) {
    return error;
  }
  return new RequestError("Network request failed. Please check your connection and retry.", {
    status: 0,
    code: "NETWORK_ERROR"
  });
};
