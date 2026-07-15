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
      failed.push("GitHub");
    }
    if (results[1].status === "fulfilled") {
      mysqlConfig.value = results[1].value;
      applyServiceConfig("mysql", results[1].value);
    } else {
      failed.push("MySQL");
    }
    if (results[2].status === "fulfilled") {
      rabbitMqConfig.value = results[2].value;
      applyServiceConfig("rabbitmq", results[2].value);
    } else {
      failed.push("RabbitMQ");
    }
    if (results[3].status === "fulfilled") {
      reviewPolicyConfig.value = results[3].value;
      applyReviewPolicyConfig(results[3].value);
    } else {
      failed.push("Review Policy");
    }

    return failed;
  };

  const loadConfig = async () => {
    loading.value = true;
    try {
      const failed = await loadConfigResults();
      if (failed.length > 0) {
        ElMessage.warning(`Config load failed for: ${failed.join(", ")}. Available configs were loaded.`);
      }
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
      const results = await Promise.allSettled([
        requests.updateGithubIntegrationConfig(payloads.githubPayload()),
        requests.updateMysqlIntegrationConfig(payloads.mysqlPayload()),
        requests.updateRabbitMqIntegrationConfig(payloads.rabbitMqPayload()),
        requests.updateReviewPolicyConfig(payloads.springAiPayload())
      ] as const);
      const labels = ["GitHub", "MySQL", "RabbitMQ", "Review Policy"] as const;
      const succeeded = results
        .map((result, index) => (result.status === "fulfilled" ? labels[index] : undefined))
        .filter((label): label is (typeof labels)[number] => Boolean(label));
      const failed = results
        .map((result, index) =>
          result.status === "rejected"
            ? `${labels[index]} (${getErrorMessage(result.reason, "unknown error")})`
            : undefined
        )
        .filter((label): label is string => Boolean(label));

      if (
        results[0].status === "fulfilled"
        && results[1].status === "fulfilled"
        && results[2].status === "fulfilled"
        && results[3].status === "fulfilled"
      ) {
        applyLoadedConfigs(results[0].value, results[1].value, results[2].value, results[3].value);
        ElMessage.success(`Config saved: ${succeeded.join(", ")}`);
        return;
      }

      const syncFailed = await loadConfigResults();
      const syncDetail = syncFailed.length > 0
        ? ` Server resync also failed for: ${syncFailed.join(", ")}.`
        : " Server state has been reloaded.";
      ElMessage.error(
        `Config partially saved. Succeeded: ${succeeded.join(", ") || "none"}. Failed: ${failed.join(", ")}.${syncDetail}`
      );
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
