import type {
  ApiEndpoint,
  QueryParams
} from "@/api/endpoint";
import {
  generatedOpenApiOperations,
  type GeneratedOpenApiBody,
  type GeneratedOpenApiOperationId,
  type GeneratedOpenApiPathParams,
  type GeneratedOpenApiQuery,
  type GeneratedOpenApiResponse
} from "@/api/generated/openapiOperations";

type AdapterSection<
  Value,
  Key extends "path" | "query" | "body",
  Input
> = [Value] extends [never]
  ? { [Property in Key]?: never }
  : { [Property in Key]: (input: Input) => Value };

export type GeneratedEndpointAdapter<
  Operation extends GeneratedOpenApiOperationId,
  Input
> = AdapterSection<GeneratedOpenApiPathParams<Operation>, "path", Input>
  & AdapterSection<GeneratedOpenApiQuery<Operation>, "query", Input>
  & AdapterSection<GeneratedOpenApiBody<Operation>, "body", Input>;

export const generatedEndpoint = <
  Operation extends GeneratedOpenApiOperationId,
  Input
>(
  operationId: Operation,
  adapter: GeneratedEndpointAdapter<Operation, Input>
): ApiEndpoint<Input, GeneratedOpenApiResponse<Operation>> => {
  const metadata = generatedOpenApiOperations[operationId];
  const endpoint: ApiEndpoint<Input, GeneratedOpenApiResponse<Operation>> = {
    path: input => resolvePath(
      metadata.path,
      metadata.pathParamNames,
      adapter.path?.(input) as Record<string, string | number> | undefined
    )
  };

  if (metadata.method !== "GET") {
    endpoint.method = metadata.method;
  }
  if (metadata.queryParamNames.length > 0) {
    endpoint.query = input => toQueryParams(
      metadata.queryParamNames,
      adapter.query?.(input) as Record<string, string | number | boolean | undefined> | undefined
    );
  }
  if (metadata.hasRequestBody) {
    endpoint.body = input => {
      const body = adapter.body?.(input);
      if (metadata.requestBodyRequired && body === undefined) {
        throw new Error(`Missing generated OpenAPI request body: ${String(operationId)}`);
      }
      return body;
    };
  }
  return endpoint;
};

const resolvePath = (
  template: string,
  parameterNames: readonly string[],
  parameters: Record<string, string | number> | undefined
) => {
  let path = template;
  for (const parameterName of parameterNames) {
    const value = parameters?.[parameterName];
    if (value === undefined || value === null || value === "") {
      throw new Error(`Missing generated OpenAPI path parameter: ${parameterName}`);
    }
    path = path.replace(`{${parameterName}}`, encodeURIComponent(String(value)));
  }
  return path;
};

const toQueryParams = (
  parameterNames: readonly string[],
  parameters: Record<string, string | number | boolean | undefined> | undefined
): QueryParams => Object.fromEntries(parameterNames.map(parameterName => {
  const value = parameters?.[parameterName];
  return [
    parameterName,
    typeof value === "boolean" ? String(value) : value
  ];
}));
