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

export const useIntegrationConfigPersistence = ({
  applyGithubConfig,
  applyReviewPolicyConfig,
  applyServiceConfig,
  canManage,
  payloads,
  requests
}: UseIntegrationConfigPersistenceOptions) => {
  const loading = ref(false);
  const saving = ref(false);
  const githubConfig = ref<GithubIntegrationConfig>();
  const mysqlConfig = ref<ServiceIntegrationConfig>();
  const rabbitMqConfig = ref<ServiceIntegrationConfig>();
  const reviewPolicyConfig = ref<ReviewPolicyConfig>();

  const applyLoadedConfigs = (
    github: GithubIntegrationConfig,
    mysql: ServiceIntegrationConfig,
    rabbitMq: ServiceIntegrationConfig,
    reviewPolicy: ReviewPolicyConfig
  ) => {
    githubConfig.value = github;
    mysqlConfig.value = mysql;
    rabbitMqConfig.value = rabbitMq;
    reviewPolicyConfig.value = reviewPolicy;
    applyGithubConfig(github);
    applyServiceConfig("mysql", mysql);
    applyServiceConfig("rabbitmq", rabbitMq);
    applyReviewPolicyConfig(reviewPolicy);
  };

  const loadConfig = async () => {
    loading.value = true;
    try {
      const [github, mysql, rabbitMq, reviewPolicy] = await Promise.all([
        requests.fetchGithubIntegrationConfig(),
        requests.fetchMysqlIntegrationConfig(),
        requests.fetchRabbitMqIntegrationConfig(),
        requests.fetchReviewPolicyConfig()
      ]);
      applyLoadedConfigs(github, mysql, rabbitMq, reviewPolicy);
    } catch (error) {
      ElMessage.warning(getErrorMessage(error, "Config load failed, using local defaults"));
    } finally {
      loading.value = false;
    }
  };

  const saveConfig = async () => {
    if (!canManage.value || saving.value) {
      return;
    }
    saving.value = true;
    try {
      const [github, mysql, rabbitMq, reviewPolicy] = await Promise.all([
        requests.updateGithubIntegrationConfig(payloads.githubPayload()),
        requests.updateMysqlIntegrationConfig(payloads.mysqlPayload()),
        requests.updateRabbitMqIntegrationConfig(payloads.rabbitMqPayload()),
        requests.updateReviewPolicyConfig(payloads.springAiPayload())
      ]);
      applyLoadedConfigs(github, mysql, rabbitMq, reviewPolicy);
      ElMessage.success("Config saved");
    } catch (error) {
      ElMessage.error(getErrorMessage(error, "Config save failed"));
    } finally {
      saving.value = false;
    }
  };

  return {
    githubConfig,
    loading,
    mysqlConfig,
    rabbitMqConfig,
    reviewPolicyConfig,
    saving,
    loadConfig,
    saveConfig
  };
};
