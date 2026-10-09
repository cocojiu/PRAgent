import { getCurrentScope, onScopeDispose, ref } from "vue";
import type { ApiRequestOptions } from "@/api/contracts";
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
  fetchGithubIntegrationConfig: (options?: ApiRequestOptions) => Promise<GithubIntegrationConfig>;
  fetchMysqlIntegrationConfig: (options?: ApiRequestOptions) => Promise<ServiceIntegrationConfig>;
  fetchRabbitMqIntegrationConfig: (options?: ApiRequestOptions) => Promise<ServiceIntegrationConfig>;
  fetchReviewPolicyConfig: (options?: ApiRequestOptions) => Promise<ReviewPolicyConfig>;
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

type FormSnapshot = Readonly<Record<string, string>>;
type UseIntegrationConfigPersistenceOptions = {
  applyGithubConfig: (config: GithubIntegrationConfig, baseline?: FormSnapshot) => void;
  applyReviewPolicyConfig: (config: ReviewPolicyConfig, baseline?: FormSnapshot) => void;
  applyServiceConfig: (id: "mysql" | "rabbitmq", config: ServiceIntegrationConfig, baseline?: FormSnapshot) => void;
  captureForm?: (id: IntegrationId) => FormSnapshot;
  captureSavedForm?: (id: IntegrationId) => FormSnapshot;
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
  captureForm,
  captureSavedForm,
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
  const writeVersions: Record<IntegrationId, number> = { github: 0, mysql: 0, rabbitmq: 0, "spring-ai": 0 };
  let loadController: AbortController | undefined;
  let disposed = false;

  const applyGithub = (config: GithubIntegrationConfig, baseline?: FormSnapshot) => {
    if (baseline) applyGithubConfig(config, baseline);
    else applyGithubConfig(config);
  };
  const applyPolicy = (config: ReviewPolicyConfig, baseline?: FormSnapshot) => {
    if (baseline) applyReviewPolicyConfig(config, baseline);
    else applyReviewPolicyConfig(config);
  };
  const applyService = (id: "mysql" | "rabbitmq", config: ServiceIntegrationConfig, baseline?: FormSnapshot) => {
    if (baseline) applyServiceConfig(id, config, baseline);
    else applyServiceConfig(id, config);
  };

  const loadConfig = async () => {
    if (disposed) return;
    loadController?.abort();
    const controller = new AbortController();
    loadController = controller;
    const current = () => !disposed && loadController === controller && !controller.signal.aborted;
    const versions = { ...writeVersions };
    const canApply = (id: IntegrationId) => versions[id] === writeVersions[id] && savingId.value !== id;
    const baselines = {
      github: captureSavedForm?.("github"), mysql: captureSavedForm?.("mysql"),
      rabbitmq: captureSavedForm?.("rabbitmq"), "spring-ai": captureSavedForm?.("spring-ai")
    };
    loading.value = true;
    loadErrorMessage.value = "";
    try {
      const results = await Promise.allSettled([
        requests.fetchGithubIntegrationConfig({ signal: controller.signal }),
        requests.fetchMysqlIntegrationConfig({ signal: controller.signal }),
        requests.fetchRabbitMqIntegrationConfig({ signal: controller.signal }),
        requests.fetchReviewPolicyConfig({ signal: controller.signal })
      ] as const);
      if (!current()) return;
      const failed: string[] = [];

      if (canApply("github") && results[0].status === "fulfilled") {
        githubConfig.value = results[0].value;
        applyGithub(results[0].value, baselines.github);
      } else if (canApply("github") && results[0].status === "rejected") {
        failed.push(integrationConfigLabels[0]);
      }
      if (canApply("mysql") && results[1].status === "fulfilled") {
        mysqlConfig.value = results[1].value;
        applyService("mysql", results[1].value, baselines.mysql);
      } else if (canApply("mysql") && results[1].status === "rejected") {
        failed.push(integrationConfigLabels[1]);
      }
      if (canApply("rabbitmq") && results[2].status === "fulfilled") {
        rabbitMqConfig.value = results[2].value;
        applyService("rabbitmq", results[2].value, baselines.rabbitmq);
      } else if (canApply("rabbitmq") && results[2].status === "rejected") {
        failed.push(integrationConfigLabels[2]);
      }
      if (canApply("spring-ai") && results[3].status === "fulfilled") {
        reviewPolicyConfig.value = results[3].value;
        applyPolicy(results[3].value, baselines["spring-ai"]);
      } else if (canApply("spring-ai") && results[3].status === "rejected") {
        failed.push(integrationConfigLabels[3]);
      }

      if (failed.length > 0) {
        loadErrorMessage.value = integrationConfigMessages.loadFailed(failed);
      }
    } finally {
      if (current()) loading.value = false;
    }
  };

  const saveConfig = async (id: string) => {
    if (disposed || !canManage.value || savingId.value || !isIntegrationId(id)) {
      return;
    }
    savingId.value = id;
    writeVersions[id] += 1;
    const baseline = captureForm?.(id);
    try {
      if (id === "github") {
        const config = await requests.updateGithubIntegrationConfig(payloads.githubPayload());
        if (disposed) return;
        githubConfig.value = config;
        applyGithub(config, baseline);
      } else if (id === "mysql") {
        const config = await requests.updateMysqlIntegrationConfig(payloads.mysqlPayload());
        if (disposed) return;
        mysqlConfig.value = config;
        applyService("mysql", config, baseline);
      } else if (id === "rabbitmq") {
        const config = await requests.updateRabbitMqIntegrationConfig(payloads.rabbitMqPayload());
        if (disposed) return;
        rabbitMqConfig.value = config;
        applyService("rabbitmq", config, baseline);
      } else {
        const config = await requests.updateReviewPolicyConfig(payloads.springAiPayload());
        if (disposed) return;
        reviewPolicyConfig.value = config;
        applyPolicy(config, baseline);
      }
      ElMessage.success(`${integrationLabelById[id]} 配置保存成功`);
    } catch (error) {
      if (!disposed) ElMessage.error(`${integrationLabelById[id]} 配置保存失败：${getErrorMessage(error)}`);
    } finally {
      writeVersions[id] += 1;
      savingId.value = undefined;
    }
  };

  if (getCurrentScope()) onScopeDispose(() => {
    disposed = true;
    loadController?.abort();
    loading.value = false;
  });

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
