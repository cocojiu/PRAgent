<template>
  <div v-loading="loading" class="integration-page">
    <div class="integration-header">
      <div>
        <h1>集成配置</h1>
      </div>
      <el-button type="primary" size="large" :loading="saving" @click="saveConfig">
        <Save :size="17" />
        保存配置
      </el-button>
    </div>

    <el-alert
      title="配置系统所需的外部服务连接信息。密钥字段保存后只会显示脱敏值。"
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
        @test-connection="testConnection"
      />
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import { Database, Github, Hexagon, RadioTower, Save } from "lucide-vue-next";
import type { Component } from "vue";
import {
  fetchGithubIntegrationConfig,
  fetchReviewPolicyConfig,
  updateGithubIntegrationConfig,
  updateReviewPolicyConfig
} from "@/api/config";
import IntegrationCard from "@/components/IntegrationCard.vue";
import { integrations } from "@/mocks/integrations";
import type { GithubIntegrationConfig, IntegrationConfig, ReviewPolicyConfig } from "@/types";

const serviceIcons: Record<string, Component> = {
  github: Github,
  mysql: Database,
  rabbitmq: RadioTower,
  "spring-ai": Hexagon
};

const loading = ref(false);
const saving = ref(false);
const githubConfig = ref<GithubIntegrationConfig>();
const reviewPolicyConfig = ref<ReviewPolicyConfig>();
const integrationItems = ref<IntegrationConfig[]>(
  integrations.map((item) => ({ ...item, fields: item.fields.map((field) => ({ ...field })) }))
);

const formState = reactive<Record<string, Record<string, string>>>(
  Object.fromEntries(integrations.map((item) => [item.id, Object.fromEntries(item.fields.map((field) => [field.label, field.value]))]))
);

const visibleSecrets = reactive<Record<string, boolean>>({});

const providerMap: Record<string, string> = {
  dashscope: "DashScope",
  openai: "OpenAI Compatible",
  mock: "Mock"
};

const reverseProviderMap = computed(() =>
  Object.fromEntries(Object.entries(providerMap).map(([value, label]) => [label, value]))
);

const testConnection = (name: string) => {
  ElMessage.success(`${name} test triggered`);
};

const loadConfig = async () => {
  loading.value = true;
  try {
    const [github, reviewPolicy] = await Promise.all([
      fetchGithubIntegrationConfig(),
      fetchReviewPolicyConfig()
    ]);
    githubConfig.value = github;
    reviewPolicyConfig.value = reviewPolicy;
    applyGithubConfig(github);
    applyReviewPolicyConfig(reviewPolicy);
  } catch (error) {
    ElMessage.warning(error instanceof Error ? error.message : "Config load failed, using local defaults");
  } finally {
    loading.value = false;
  }
};

const saveConfig = async () => {
  saving.value = true;
  try {
    const githubPayload = {
      baseUrl: formState.github["API Base URL"] || "https://api.github.com",
      token: formState.github.Token,
      defaultOwner: formState.github["Default Owner"],
      defaultRepo: formState.github["Default Repo"]
    };
    const springAiPayload = {
      llmEnabled: true,
      llmProvider: reverseProviderMap.value[formState["spring-ai"].Provider] ?? "dashscope",
      modelName: formState["spring-ai"].Model || "qwen-plus",
      baseUrl: formState["spring-ai"]["Base URL"],
      apiKey: formState["spring-ai"]["API Key"],
      timeoutSeconds: reviewPolicyConfig.value?.timeoutSeconds ?? 60,
      temperature: reviewPolicyConfig.value?.temperature ?? 0.2,
      maxTokens: reviewPolicyConfig.value?.maxTokens ?? 4096,
      fallbackToRules: reviewPolicyConfig.value?.fallbackToRules ?? true,
      workerConcurrency: reviewPolicyConfig.value?.workerConcurrency ?? 1
    };

    const [github, reviewPolicy] = await Promise.all([
      updateGithubIntegrationConfig(githubPayload),
      updateReviewPolicyConfig(springAiPayload)
    ]);
    githubConfig.value = github;
    reviewPolicyConfig.value = reviewPolicy;
    applyGithubConfig(github);
    applyReviewPolicyConfig(reviewPolicy);
    ElMessage.success("Config saved");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "Config save failed");
  } finally {
    saving.value = false;
  }
};

const applyGithubConfig = (config: GithubIntegrationConfig) => {
  const item = integrationItems.value.find((integration) => integration.id === "github");
  if (!item) {
    return;
  }
  item.status = config.status === "configured" ? "connected" : config.status === "failed" ? "failed" : "missing_secret";
  item.statusText = config.status === "configured" ? "已连接" : config.status === "failed" ? "连接失败" : "缺少 Token";
  item.metaLabel = "更新时间";
  item.metaValue = config.updatedAt ?? "未更新";
  item.message = config.lastError ?? (config.status === "configured" ? "GitHub 配置已保存" : "请配置 GitHub Token");
  item.fields = [
    { label: "API Base URL", value: config.baseUrl, type: "text" },
    { label: "Token", value: config.token ?? "", type: "password", placeholder: "GitHub token" },
    { label: "Default Owner", value: config.defaultOwner ?? "", type: "text" },
    { label: "Default Repo", value: config.defaultRepo ?? "", type: "text" }
  ];
  formState.github = Object.fromEntries(item.fields.map((field) => [field.label, field.value]));
};

const applyReviewPolicyConfig = (config: ReviewPolicyConfig) => {
  const item = integrationItems.value.find((integration) => integration.id === "spring-ai");
  if (!item) {
    return;
  }
  item.status = config.apiKey ? "connected" : "missing_secret";
  item.statusText = config.apiKey ? "已连接" : "缺少 API Key";
  item.metaLabel = "模型名称";
  item.metaValue = config.modelName;
  item.message = config.apiKey ? "LLM 配置已保存" : "请配置 LLM API Key";
  item.fields = [
    {
      label: "Provider",
      value: providerMap[config.llmProvider] ?? config.llmProvider,
      type: "select",
      options: Object.values(providerMap)
    },
    { label: "API Key", value: config.apiKey ?? "", type: "password", placeholder: "LLM API key" },
    { label: "Model", value: config.modelName, type: "text" },
    { label: "Base URL", value: config.baseUrl ?? "", type: "text" }
  ];
  formState["spring-ai"] = Object.fromEntries(item.fields.map((field) => [field.label, field.value]));
};

onMounted(() => {
  void loadConfig();
});
</script>
