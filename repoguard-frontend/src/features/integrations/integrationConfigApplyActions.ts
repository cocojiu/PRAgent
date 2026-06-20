import type {
  ConnectionTestResult,
  GithubIntegrationConfig,
  IntegrationConfig,
  ReviewPolicyConfig,
  ServiceIntegrationConfig
} from "@/types";
import {
  buildConnectionTestPatch,
  buildGithubIntegrationPatch,
  buildReviewPolicyIntegrationPatch,
  buildServiceIntegrationPatch
} from "./integrationConfigMappers";

type BuildIntegrationConfigApplyActionsOptions = {
  applyIntegrationPatch: (id: string, patch: Partial<IntegrationConfig>) => void;
};

export const buildIntegrationConfigApplyActions = ({
  applyIntegrationPatch
}: BuildIntegrationConfigApplyActionsOptions) => {
  const applyGithubConfig = (config: GithubIntegrationConfig) => {
    applyIntegrationPatch("github", buildGithubIntegrationPatch(config));
  };

  const applyServiceConfig = (id: "mysql" | "rabbitmq", config: ServiceIntegrationConfig) => {
    applyIntegrationPatch(id, buildServiceIntegrationPatch(id, config));
  };

  const applyReviewPolicyConfig = (config: ReviewPolicyConfig) => {
    applyIntegrationPatch("spring-ai", buildReviewPolicyIntegrationPatch(config));
  };

  const applyConnectionTestResult = (id: string, result: ConnectionTestResult) => {
    applyIntegrationPatch(id, buildConnectionTestPatch(id, result));
  };

  return {
    applyConnectionTestResult,
    applyGithubConfig,
    applyReviewPolicyConfig,
    applyServiceConfig
  };
};
