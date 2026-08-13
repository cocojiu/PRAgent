import type { ApiResponseValidator } from "@/api/responseValidation";

export type HttpMethod = "GET" | "POST" | "PUT" | "DELETE";
export type QueryParams = Record<string, string | number | undefined>;

export type ApiOperation<Input, Response> = {
  input: Input;
  response: Response;
};

export type ApiEndpoint<Input, Response> = {
  readonly __responseType?: (value: Response) => Response;
  method?: HttpMethod;
  path: (input: Input) => string;
  query?: (input: Input) => QueryParams;
  body?: (input: Input) => unknown;
  observe?: boolean;
  validateResponse?: ApiResponseValidator<Response>;
};
