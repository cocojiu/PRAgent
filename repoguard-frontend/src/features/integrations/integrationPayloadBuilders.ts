import type {
  GithubIntegrationConfigRequest,
  ReviewPolicyConfig,
  ReviewPolicyConfigRequest,
  ServiceIntegrationConfigRequest
} from "@/types";
import { providerMap, type IntegrationId } from "./integrationDefaults";

export type IntegrationFormState = Record<string, Record<string, string>>;

export const integrationFieldValue = (formState: IntegrationFormState, id: IntegrationId, label: string) =>
  formState[id]?.[label] ?? "";

const numberFieldValue = (formState: IntegrationFormState, id: IntegrationId, label: string, fallback: number) => {
  const parsed = Number(integrationFieldValue(formState, id, label));
  return Number.isFinite(parsed) ? parsed : fallback;
};

const reverseProviderMap = () =>
  Object.fromEntries(Object.entries(providerMap).map(([value, label]) => [label, value]));

export const buildGithubPayload = (formState: IntegrationFormState): GithubIntegrationConfigRequest => ({
  baseUrl: integrationFieldValue(formState, "github", "API Base URL").trim() || "https://api.github.com",
  token: integrationFieldValue(formState, "github", "Token"),
  defaultOwner: integrationFieldValue(formState, "github", "Default Owner"),
  defaultRepo: integrationFieldValue(formState, "github", "Default Repo")
});

export const buildMysqlPayload = (formState: IntegrationFormState): ServiceIntegrationConfigRequest => ({
  baseUrl: integrationFieldValue(formState, "mysql", "JDBC URL").trim(),
  username: integrationFieldValue(formState, "mysql", "Username"),
  secret: integrationFieldValue(formState, "mysql", "Password"),
  resource: integrationFieldValue(formState, "mysql", "Database")
});

export const buildRabbitMqPayload = (formState: IntegrationFormState): ServiceIntegrationConfigRequest => ({
  baseUrl: integrationFieldValue(formState, "rabbitmq", "AMQP URL").trim(),
  username: integrationFieldValue(formState, "rabbitmq", "Username"),
  secret: integrationFieldValue(formState, "rabbitmq", "Password"),
  resource: integrationFieldValue(formState, "rabbitmq", "Virtual Host")
});

export const buildSpringAiPayload = (
  formState: IntegrationFormState,
  reviewPolicyConfig?: ReviewPolicyConfig
): ReviewPolicyConfigRequest => {
  const providers = reverseProviderMap();
  return {
    llmEnabled: true,
    llmProvider: providers[integrationFieldValue(formState, "spring-ai", "Provider")] ?? "dashscope",
    modelName: integrationFieldValue(formState, "spring-ai", "Model").trim() || "qwen-plus",
    baseUrl: integrationFieldValue(formState, "spring-ai", "Base URL"),
    apiKey: integrationFieldValue(formState, "spring-ai", "API Key"),
    timeoutSeconds: reviewPolicyConfig?.timeoutSeconds ?? 60,
    temperature: reviewPolicyConfig?.temperature ?? 0.2,
    maxTokens: reviewPolicyConfig?.maxTokens ?? 4096,
    fallbackToRules: reviewPolicyConfig?.fallbackToRules ?? true,
    workerConcurrency: reviewPolicyConfig?.workerConcurrency ?? 1,
    chunkFileThreshold: numberFieldValue(
      formState,
      "spring-ai",
      "Chunk File Threshold",
      reviewPolicyConfig?.chunkFileThreshold ?? 6
    ),
    chunkLineThreshold: numberFieldValue(
      formState,
      "spring-ai",
      "Chunk Line Threshold",
      reviewPolicyConfig?.chunkLineThreshold ?? 700
    ),
    chunkMaxFiles: numberFieldValue(formState, "spring-ai", "Chunk Max Files", reviewPolicyConfig?.chunkMaxFiles ?? 4),
    chunkMaxLines: numberFieldValue(formState, "spring-ai", "Chunk Max Lines", reviewPolicyConfig?.chunkMaxLines ?? 450),
    inputTokenPricePerMillion: numberFieldValue(
      formState,
      "spring-ai",
      "Input $/1M Tokens",
      reviewPolicyConfig?.inputTokenPricePerMillion ?? 0
    ),
    outputTokenPricePerMillion: numberFieldValue(
      formState,
      "spring-ai",
      "Output $/1M Tokens",
      reviewPolicyConfig?.outputTokenPricePerMillion ?? 0
    )
  };
};
