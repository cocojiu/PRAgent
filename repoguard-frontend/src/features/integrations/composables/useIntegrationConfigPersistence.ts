import { ref } from "vue";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import type {
  GithubIntegrationConfig,
  GithubIntegrationConfigRequest,
  ReviewPolicyConfig,
  ReviewPolicyConfigRequest,
  ServiceIntegrationConfig,
  ServiceIntegrationConfigRequest
} from "@/types";
import { getErrorMessage } from "@/utils/errors";
import {
  integrationConfigLabels,
  integrationConfigMessages
} from "@/utils/userMessages";
import type { IntegrationId } from "../integrationDefaults";

type IntegrationConfigRequestActions = {
  fetchGithubIntegrationConfig: () => Promise<GithubIntegrationConfig>;
  fetchMysqlIntegrationConfig: () => Promise<ServiceIntegrationConfig>;
  fetchRabbitMqIntegrationConfig: () => Promise<ServiceIntegrationConfig>;
  fetchReviewPolicyConfig: () => Promise<ReviewPolicyConfig>;
  updateGithubIntegrationConfig: (payload: GithubIntegrationConfigRequest) => Promise<GithubIntegrationConfig>;
  updateMysqlIntegrationConfig: (payload: ServiceIntegrationConfigRequest) => Promise<ServiceIntegrationConfig>;
  updateRabbitMqIntegrationConfig: (payload: ServiceIntegrationConfigRequest) => Promise<ServiceIntegrationConfig>;
  updateReviewPolicyConfig: (payload: ReviewPolicyConfigRequest) => Promise<ReviewPolicyConfig>;
};

type IntegrationConfigPayloadGetters = {
  githubPayload: () => GithubIntegrationConfigRequest;
  mysqlPayload: () => ServiceIntegrationConfigRequest;
  rabbitMqPayload: () => ServiceIntegrationConfigRequest;
  springAiPayload: () => ReviewPolicyConfigRequest;
};

type UseIntegrationConfigPersistenceOptions = {
  applyGithubConfig: (config: GithubIntegrationConfig) => void;
  applyReviewPolicyConfig: (config: ReviewPolicyConfig) => void;
  applyServiceConfig: (id: "mysql" | "rabbitmq", config: ServiceIntegrationConfig) => void;
  canManage: { value: boolean };
  payloads: IntegrationConfigPayloadGetters;
  requests: IntegrationConfigRequestActions;
};

const integrationLabelById: Record<IntegrationId, string> = {
  github: integrationConfigLabels[0],
  mysql: integrationConfigLabels[1],
  rabbitmq: integrationConfigLabels[2],
  "spring-ai": integrationConfigLabels[3]
};

const isIntegrationId = (id: string): id is IntegrationId =>
  ["github", "mysql", "rabbitmq", "spring-ai"].includes(id);

export const useIntegrationConfigPersistence = ({
  applyGithubConfig,
  applyReviewPolicyConfig,
  applyServiceConfig,
  canManage,
  payloads,
  requests
}: UseIntegrationConfigPersistenceOptions) => {
  const loading = ref(false);
  const loadErrorMessage = ref("");
  const savingId = ref<IntegrationId>();
  const githubConfig = ref<GithubIntegrationConfig>();
  const mysqlConfig = ref<ServiceIntegrationConfig>();
  const rabbitMqConfig = ref<ServiceIntegrationConfig>();
  const reviewPolicyConfig = ref<ReviewPolicyConfig>();

  const loadConfigResults = async () => {
    const results = await Promise.allSettled([
      requests.fetchGithubIntegrationConfig(),
      requests.fetchMysqlIntegrationConfig(),
      requests.fetchRabbitMqIntegrationConfig(),
      requests.fetchReviewPolicyConfig()
    ] as const);
    const failed: string[] = [];

    if (results[0].status === "fulfilled") {
      githubConfig.value = results[0].value;
      applyGithubConfig(results[0].value);
    } else {
      failed.push(integrationConfigLabels[0]);
    }
    if (results[1].status === "fulfilled") {
      mysqlConfig.value = results[1].value;
      applyServiceConfig("mysql", results[1].value);
    } else {
      failed.push(integrationConfigLabels[1]);
    }
    if (results[2].status === "fulfilled") {
      rabbitMqConfig.value = results[2].value;
      applyServiceConfig("rabbitmq", results[2].value);
    } else {
      failed.push(integrationConfigLabels[2]);
    }
    if (results[3].status === "fulfilled") {
      reviewPolicyConfig.value = results[3].value;
      applyReviewPolicyConfig(results[3].value);
    } else {
      failed.push(integrationConfigLabels[3]);
    }

    return failed;
  };

  const loadConfig = async () => {
    loading.value = true;
    loadErrorMessage.value = "";
    try {
      const failed = await loadConfigResults();
      if (failed.length > 0) {
        loadErrorMessage.value = integrationConfigMessages.loadFailed(failed);
      }
    } finally {
      loading.value = false;
    }
  };

  const saveConfig = async (id: string) => {
    if (!canManage.value || savingId.value || !isIntegrationId(id)) {
      return;
    }
    savingId.value = id;
    try {
      if (id === "github") {
        const config = await requests.updateGithubIntegrationConfig(payloads.githubPayload());
        githubConfig.value = config;
        applyGithubConfig(config);
      } else if (id === "mysql") {
        const config = await requests.updateMysqlIntegrationConfig(payloads.mysqlPayload());
        mysqlConfig.value = config;
        applyServiceConfig("mysql", config);
      } else if (id === "rabbitmq") {
        const config = await requests.updateRabbitMqIntegrationConfig(payloads.rabbitMqPayload());
        rabbitMqConfig.value = config;
        applyServiceConfig("rabbitmq", config);
      } else {
        const config = await requests.updateReviewPolicyConfig(payloads.springAiPayload());
        reviewPolicyConfig.value = config;
        applyReviewPolicyConfig(config);
      }
      ElMessage.success(`${integrationLabelById[id]} 配置保存成功`);
    } catch (error) {
      ElMessage.error(`${integrationLabelById[id]} 配置保存失败：${getErrorMessage(error)}`);
    } finally {
      savingId.value = undefined;
    }
  };

  return {
    githubConfig,
    loadErrorMessage,
    loading,
    mysqlConfig,
    rabbitMqConfig,
    reviewPolicyConfig,
    savingId,
    loadConfig,
    saveConfig
  };
};
