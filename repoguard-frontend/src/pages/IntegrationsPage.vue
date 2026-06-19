<template>
  <div v-loading="loading" class="integration-page">
    <div class="integration-header">
      <div>
        <h1>集成配置</h1>
      </div>
      <el-button type="primary" size="large" :disabled="!canManage" :loading="saving" @click="saveConfig">
        <Save :size="17" />
        保存配置
      </el-button>
    </div>

    <el-alert
      title="GitHub 与 LLM 配置会参与审查链路；MySQL 与 RabbitMQ 配置用于页面检测和运维核对，保存后不会动态切换当前运行中的数据源或消息队列连接。密钥字段保存后只会显示脱敏值。"
      type="primary"
      :closable="false"
      show-icon
      class="integration-alert"
    />

    <section class="integration-list">
      <IntegrationCard
        v-for="item in integrationItems"
        :key="item.id"
        :item="item"
        :icon="serviceIcons[item.id] ?? Hexagon"
        :form-state="formState[item.id]"
        :visible-secrets="visibleSecrets"
        :testing="testingConnections[item.id]"
        @test-connection="testConnection"
      />
    </section>

  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { Hexagon, Save } from "lucide-vue-next";
import { canManage } from "@/stores/authState";
import {
  fetchGithubIntegrationConfig,
  fetchMysqlIntegrationConfig,
  fetchRabbitMqIntegrationConfig,
  fetchReviewPolicyConfig,
  testGithubIntegrationConnection,
  testMysqlConnection,
  testRabbitMqConnection,
  testReviewPolicyConnection,
  updateGithubIntegrationConfig,
  updateMysqlIntegrationConfig,
  updateRabbitMqIntegrationConfig,
  updateReviewPolicyConfig
} from "@/api/config";
import IntegrationCard from "@/components/IntegrationCard.vue";
import {
  buildConnectionTestPatch,
  buildGithubPayload,
  buildGithubIntegrationPatch,
  buildMysqlPayload,
  buildRabbitMqPayload,
  buildReviewPolicyIntegrationPatch,
  buildServiceIntegrationPatch,
  buildSpringAiPayload,
  cloneIntegrationItems,
  defaultIntegrationItems,
  serviceIcons,
  useIntegrationConfigPersistence,
  useIntegrationConnectionTest,
  type IntegrationFormState
} from "@/features/integrations";
import type {
  ConnectionTestResult,
  GithubIntegrationConfig,
  ReviewPolicyConfig,
  ServiceIntegrationConfig
} from "@/types";

const integrationItems = ref(cloneIntegrationItems());

const formState = reactive<IntegrationFormState>(
  Object.fromEntries(defaultIntegrationItems.map((item) => [item.id, Object.fromEntries(item.fields.map((field) => [field.label, field.value]))]))
);

const visibleSecrets = reactive<Record<string, boolean>>({});
const testingConnections = reactive<Record<string, boolean>>({});

const formValues = (fields: { label: string; value: string }[]) =>
  Object.fromEntries(fields.map((field) => [field.label, field.value]));

const applyIntegrationPatch = (id: string, patch: Partial<typeof integrationItems.value[number]>) => {
  const item = integrationItems.value.find((integration) => integration.id === id);
  if (!item) {
    return;
  }
  Object.assign(item, patch);
  if (patch.fields) {
    formState[id] = formValues(patch.fields);
  }
};

const testActions: Record<string, () => Promise<ConnectionTestResult>> = {
  github: () => testGithubIntegrationConnection(githubPayload()),
  mysql: () => testMysqlConnection(mysqlPayload()),
  rabbitmq: () => testRabbitMqConnection(rabbitMqPayload()),
  "spring-ai": () => testReviewPolicyConnection(springAiPayload())
};

const { testConnection } = useIntegrationConnectionTest({
  applyConnectionTestResult: (id, result) => applyConnectionTestResult(id, result),
  hasIntegration: (id) => integrationItems.value.some((integration) => integration.id === id),
  testActions,
  testingConnections
});

const githubPayload = () => buildGithubPayload(formState);

const mysqlPayload = () => buildMysqlPayload(formState);

const rabbitMqPayload = () => buildRabbitMqPayload(formState);

const springAiPayload = () => buildSpringAiPayload(formState, reviewPolicyConfig.value);

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

const { loading, saving, reviewPolicyConfig, loadConfig, saveConfig } = useIntegrationConfigPersistence({
  applyGithubConfig,
  applyReviewPolicyConfig,
  applyServiceConfig,
  canManage,
  payloads: {
    githubPayload,
    mysqlPayload,
    rabbitMqPayload,
    springAiPayload
  },
  requests: {
    fetchGithubIntegrationConfig,
    fetchMysqlIntegrationConfig,
    fetchRabbitMqIntegrationConfig,
    fetchReviewPolicyConfig,
    updateGithubIntegrationConfig,
    updateMysqlIntegrationConfig,
    updateRabbitMqIntegrationConfig,
    updateReviewPolicyConfig
  }
});

onMounted(() => {
  void loadConfig();
});
</script>
