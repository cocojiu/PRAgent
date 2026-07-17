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
      v-if="loadErrorMessage"
      type="warning"
      :closable="false"
      show-icon
      class="integration-alert"
    >
      <template #title>
        <span>{{ loadErrorMessage }}</span>
        <el-button link type="warning" :loading="loading" @click="loadConfig">重试</el-button>
      </template>
    </el-alert>

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
import { onMounted, reactive } from "vue";
import { Hexagon, Save } from "@lucide/vue";
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
import {
  buildGithubPayload,
  buildIntegrationConfigApplyActions,
  buildIntegrationConnectionTestActions,
  buildMysqlPayload,
  buildRabbitMqPayload,
  buildSpringAiPayload,
  IntegrationCard,
  serviceIcons,
  useIntegrationConfigPersistence,
  useIntegrationConnectionTest,
  useIntegrationFormState
} from "@/features/integrations";

const { formState, integrationItems, visibleSecrets, applyIntegrationPatch } = useIntegrationFormState();
const testingConnections = reactive<Record<string, boolean>>({});

const githubPayload = () => buildGithubPayload(formState);

const mysqlPayload = () => buildMysqlPayload(formState);

const rabbitMqPayload = () => buildRabbitMqPayload(formState);

const springAiPayload = () => buildSpringAiPayload(formState, reviewPolicyConfig.value);

const integrationPayloads = {
  githubPayload,
  mysqlPayload,
  rabbitMqPayload,
  springAiPayload
};

const connectionTestRequests = {
  testGithubIntegrationConnection,
  testMysqlConnection,
  testRabbitMqConnection,
  testReviewPolicyConnection
};

const { applyConnectionTestResult, applyGithubConfig, applyReviewPolicyConfig, applyServiceConfig } =
  buildIntegrationConfigApplyActions({
    applyIntegrationPatch
  });

const { testConnection } = useIntegrationConnectionTest({
  applyConnectionTestResult: (id, result) => applyConnectionTestResult(id, result),
  hasIntegration: (id) => integrationItems.value.some((integration) => integration.id === id),
  testActions: buildIntegrationConnectionTestActions({
    payloads: integrationPayloads,
    requests: connectionTestRequests
  }),
  testingConnections
});

const { loadErrorMessage, loading, saving, reviewPolicyConfig, loadConfig, saveConfig } = useIntegrationConfigPersistence({
  applyGithubConfig,
  applyReviewPolicyConfig,
  applyServiceConfig,
  canManage,
  payloads: integrationPayloads,
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
