import type {
  ConnectionTestResult,
  GithubIntegrationConfigRequest,
  ReviewPolicyConfigRequest,
  ServiceIntegrationConfigRequest
} from "@/types";

type IntegrationConnectionTestPayloadGetters = {
  githubPayload: () => GithubIntegrationConfigRequest;
  mysqlPayload: () => ServiceIntegrationConfigRequest;
  rabbitMqPayload: () => ServiceIntegrationConfigRequest;
  springAiPayload: () => ReviewPolicyConfigRequest;
};

type IntegrationConnectionTestRequests = {
  testGithubIntegrationConnection: (payload: GithubIntegrationConfigRequest) => Promise<ConnectionTestResult>;
  testMysqlConnection: (payload: ServiceIntegrationConfigRequest) => Promise<ConnectionTestResult>;
  testRabbitMqConnection: (payload: ServiceIntegrationConfigRequest) => Promise<ConnectionTestResult>;
  testReviewPolicyConnection: (payload: ReviewPolicyConfigRequest) => Promise<ConnectionTestResult>;
};

type BuildIntegrationConnectionTestActionsOptions = {
  payloads: IntegrationConnectionTestPayloadGetters;
  requests: IntegrationConnectionTestRequests;
};

export const buildIntegrationConnectionTestActions = ({
  payloads,
  requests
}: BuildIntegrationConnectionTestActionsOptions): Record<string, () => Promise<ConnectionTestResult>> => ({
  github: () => requests.testGithubIntegrationConnection(payloads.githubPayload()),
  mysql: () => requests.testMysqlConnection(payloads.mysqlPayload()),
  rabbitmq: () => requests.testRabbitMqConnection(payloads.rabbitMqPayload()),
  "spring-ai": () => requests.testReviewPolicyConnection(payloads.springAiPayload())
});
