import type { ApiEndpointMap } from "@/api/endpoint";
import {
  requestWithMeta
} from "@/api/client";
import type {
  ClientRequestInit
} from "@/api/client";
import type {
  ApiEndpoint,
  ApiOperation
} from "@/api/endpoint";
import {
  observeFrontendApiRequest
} from "@/observability/frontendPerformanceBuffer";
import {
  validateApiResponse
} from "@/api/responseValidation";
import {
  RequestError
} from "@/utils/errors";

export type ApiRequestOptions = {
  signal?: AbortSignal;
  keepalive?: boolean;
  timeoutMs?: number;
};

export const createApiRequester = <Contract extends { [Operation in keyof Contract]: ApiOperation<unknown, unknown> }>(
  apiEndpoints: ApiEndpointMap<Contract>
) => async <Operation extends keyof Contract>(
  operation: Operation,
  input: Contract[Operation]["input"],
  requestOptions: ApiRequestOptions = {}
): Promise<Contract[Operation]["response"]> => {
  const endpoint = apiEndpoints[operation] as ApiEndpoint<
    Contract[Operation]["input"],
    Contract[Operation]["response"]
  >;
  const options: ClientRequestInit = {
    signal: requestOptions.signal,
    keepalive: requestOptions.keepalive,
    timeoutMs: requestOptions.timeoutMs
  };
  const method = endpoint.method ?? "GET";
  const path = endpoint.path(input);
  const observationPath = stableObservationPath(path);
  const shouldObserve = endpoint.observe !== false;
  if (method !== "GET") {
    options.method = method;
  }
  const payload = endpoint.body?.(input);
  if (payload !== undefined) {
    options.body = JSON.stringify(payload);
  }
  const startedAtMs = currentTimeMs();
  try {
    const response = await requestWithMeta<unknown>(
      path,
      endpoint.query?.(input),
      options
    );
    const data = validateApiResponse(String(operation), response.data, endpoint.validateResponse, response.status);
    if (shouldObserve) {
      observeFrontendApiRequest({
        operation: String(operation),
        path: observationPath,
        method,
        status: response.status,
        result: "success",
        traceId: response.traceId,
        responseBytes: response.responseBytes,
        startedAtMs,
        durationMs: currentTimeMs() - startedAtMs
      });
    }
    return data;
  } catch (error) {
    if (shouldObserve) {
      observeFrontendApiRequest({
        operation: String(operation),
        path: observationPath,
        method,
        status: error instanceof RequestError ? error.status : undefined,
        result: "failed",
        startedAtMs,
        durationMs: currentTimeMs() - startedAtMs
      });
    }
    throw error;
  }
};

const currentTimeMs = () => (typeof performance === "undefined" ? Date.now() : performance.now());

const stableObservationPath = (path: string) =>
  path
    .split("/")
    .map(segment => {
      if (/^\d+$/.test(segment)) {
        return "{id}";
      }
      if (/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(segment)) {
        return "{uuid}";
      }
      if (/^[0-9a-f]{32,64}$/i.test(segment)) {
        return "{hash}";
      }
      return segment;
    })
    .join("/");
