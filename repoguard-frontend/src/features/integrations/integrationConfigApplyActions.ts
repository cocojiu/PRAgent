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
  applyIntegrationPatch: (id: string, patch: Partial<IntegrationConfig>, baseline?: Readonly<Record<string, string>>) => void;
};

export const buildIntegrationConfigApplyActions = ({
  applyIntegrationPatch
}: BuildIntegrationConfigApplyActionsOptions) => {
  const applyGithubConfig = (config: GithubIntegrationConfig, baseline?: Readonly<Record<string, string>>) => {
    applyIntegrationPatch("github", buildGithubIntegrationPatch(config), baseline);
  };

  const applyServiceConfig = (id: "mysql" | "rabbitmq", config: ServiceIntegrationConfig, baseline?: Readonly<Record<string, string>>) => {
    applyIntegrationPatch(id, buildServiceIntegrationPatch(id, config), baseline);
  };

  const applyReviewPolicyConfig = (config: ReviewPolicyConfig, baseline?: Readonly<Record<string, string>>) => {
    applyIntegrationPatch("spring-ai", buildReviewPolicyIntegrationPatch(config), baseline);
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
