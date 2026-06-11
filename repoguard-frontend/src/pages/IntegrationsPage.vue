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
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import { Database, Github, Hexagon, RadioTower, Save } from "lucide-vue-next";
import type { Component } from "vue";
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
import type {
  ConnectionTestResult,
  GithubIntegrationConfig,
  GithubIntegrationConfigRequest,
  IntegrationConfig,
  IntegrationDiagnosticItem,
  IntegrationField,
  ReviewPolicyConfig,
  ReviewPolicyConfigRequest,
  ServiceIntegrationConfig,
  ServiceIntegrationConfigRequest
} from "@/types";

type IntegrationId = "github" | "mysql" | "rabbitmq" | "spring-ai";

const serviceIcons: Record<string, Component> = {
  github: Github,
  mysql: Database,
  rabbitmq: RadioTower,
  "spring-ai": Hexagon
};

const defaultIntegrationItems: IntegrationConfig[] = [
  {
    id: "github",
    name: "GitHub",
    description: "用于读取 Pull Request 信息与回写审查评论",
    status: "missing_secret",
    statusText: "未配置",
    metaLabel: "更新时间",
    metaValue: "未更新",
    message: "请配置 GitHub Token",
    fields: [
      { label: "API Base URL", value: "https://api.github.com", type: "text" },
      { label: "Token", value: "", type: "password", placeholder: "GitHub token" },
      { label: "Default Owner", value: "", type: "text" },
      { label: "Default Repo", value: "", type: "text" }
    ]
  },
  {
    id: "mysql",
    name: "MySQL",
    description: "用于检测数据库连接；当前运行数据源仍由后端启动配置决定",
    status: "missing_secret",
    statusText: "未配置",
    metaLabel: "更新时间",
    metaValue: "未更新",
    message: "请配置用于检测的 MySQL 连接信息",
    fields: [
      { label: "JDBC URL", value: "", type: "text", placeholder: "jdbc:mysql://localhost:3306/repoguard" },
      { label: "Username", value: "", type: "text" },
      { label: "Password", value: "", type: "password", placeholder: "MySQL password" },
      { label: "Database", value: "", type: "text" }
    ]
  },
  {
    id: "rabbitmq",
    name: "RabbitMQ",
    description: "用于检测消息队列连接；当前运行队列仍由后端启动配置决定",
    status: "missing_secret",
    statusText: "未配置",
    metaLabel: "更新时间",
    metaValue: "未更新",
    message: "请配置用于检测的 RabbitMQ 连接信息",
    fields: [
      { label: "AMQP URL", value: "", type: "text", placeholder: "amqp://localhost:5672" },
      { label: "Username", value: "", type: "text" },
      { label: "Password", value: "", type: "password", placeholder: "RabbitMQ password" },
      { label: "Virtual Host", value: "/", type: "text" }
    ]
  },
  {
    id: "spring-ai",
    name: "Spring AI Alibaba",
    description: "用于 AI 代码审查和智能分析能力",
    status: "missing_secret",
    statusText: "缺少 API Key",
    metaLabel: "模型名称",
    metaValue: "qwen-plus",
    message: "请配置 LLM API Key",
    fields: [
      { label: "Provider", value: "DashScope", type: "select", options: ["DashScope", "OpenAI Compatible", "Mock"] },
      { label: "API Key", value: "", type: "password", placeholder: "LLM API key" },
      { label: "Model", value: "qwen-plus", type: "text" },
      { label: "Base URL", value: "https://dashscope.aliyuncs.com/compatible-mode/v1", type: "text" }
    ]
  }
];

const cloneItems = () =>
  defaultIntegrationItems.map((item) => ({ ...item, fields: item.fields.map((field) => ({ ...field })) }));

const loading = ref(false);
const saving = ref(false);
const githubConfig = ref<GithubIntegrationConfig>();
const mysqlConfig = ref<ServiceIntegrationConfig>();
const rabbitMqConfig = ref<ServiceIntegrationConfig>();
const reviewPolicyConfig = ref<ReviewPolicyConfig>();
const integrationItems = ref<IntegrationConfig[]>(cloneItems());

const formState = reactive<Record<string, Record<string, string>>>(
  Object.fromEntries(defaultIntegrationItems.map((item) => [item.id, Object.fromEntries(item.fields.map((field) => [field.label, field.value]))]))
);

const visibleSecrets = reactive<Record<string, boolean>>({});
const testingConnections = reactive<Record<string, boolean>>({});

const providerMap: Record<string, string> = {
  dashscope: "DashScope",
  openai: "OpenAI Compatible",
  mock: "Mock"
};

const reverseProviderMap = computed(() =>
  Object.fromEntries(Object.entries(providerMap).map(([value, label]) => [label, value]))
);

const testActions: Record<string, () => Promise<ConnectionTestResult>> = {
  github: () => testGithubIntegrationConnection(githubPayload()),
  mysql: () => testMysqlConnection(mysqlPayload()),
  rabbitmq: () => testRabbitMqConnection(rabbitMqPayload()),
  "spring-ai": () => testReviewPolicyConnection(springAiPayload())
};

const testConnection = async (id: string) => {
  const action = testActions[id];
  const item = integrationItems.value.find((integration) => integration.id === id);
  if (!action || !item) {
    ElMessage.warning("Connection test is not available");
    return;
  }
  testingConnections[id] = true;
  try {
    const result = await action();
    applyConnectionTestResult(id, result);
    if (result.success) {
      ElMessage.success(result.message);
    } else {
      ElMessage.error(result.message);
    }
  } catch (error) {
    const message = error instanceof Error ? error.message : "Connection test failed";
    applyConnectionTestResult(id, {
      success: false,
      status: "failed",
      message,
      checkedAt: new Date().toLocaleString()
    });
    ElMessage.error(message);
  } finally {
    testingConnections[id] = false;
  }
};

const loadConfig = async () => {
  loading.value = true;
  try {
    const [github, mysql, rabbitMq, reviewPolicy] = await Promise.all([
      fetchGithubIntegrationConfig(),
      fetchMysqlIntegrationConfig(),
      fetchRabbitMqIntegrationConfig(),
      fetchReviewPolicyConfig()
    ]);
    githubConfig.value = github;
    mysqlConfig.value = mysql;
    rabbitMqConfig.value = rabbitMq;
    reviewPolicyConfig.value = reviewPolicy;
    applyGithubConfig(github);
    applyServiceConfig("mysql", mysql);
    applyServiceConfig("rabbitmq", rabbitMq);
    applyReviewPolicyConfig(reviewPolicy);
  } catch (error) {
    ElMessage.warning(error instanceof Error ? error.message : "Config load failed, using local defaults");
  } finally {
    loading.value = false;
  }
};

const saveConfig = async () => {
  if (!canManage.value) {
    return;
  }
  saving.value = true;
  try {
    const [github, mysql, rabbitMq, reviewPolicy] = await Promise.all([
      updateGithubIntegrationConfig(githubPayload()),
      updateMysqlIntegrationConfig(mysqlPayload()),
      updateRabbitMqIntegrationConfig(rabbitMqPayload()),
      updateReviewPolicyConfig(springAiPayload())
    ]);
    githubConfig.value = github;
    mysqlConfig.value = mysql;
    rabbitMqConfig.value = rabbitMq;
    reviewPolicyConfig.value = reviewPolicy;
    applyGithubConfig(github);
    applyServiceConfig("mysql", mysql);
    applyServiceConfig("rabbitmq", rabbitMq);
    applyReviewPolicyConfig(reviewPolicy);
    ElMessage.success("Config saved");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "Config save failed");
  } finally {
    saving.value = false;
  }
};

const fieldValue = (id: IntegrationId, label: string) => formState[id]?.[label] ?? "";

const githubPayload = (): GithubIntegrationConfigRequest => ({
  baseUrl: fieldValue("github", "API Base URL").trim() || "https://api.github.com",
  token: fieldValue("github", "Token"),
  defaultOwner: fieldValue("github", "Default Owner"),
  defaultRepo: fieldValue("github", "Default Repo")
});

const mysqlPayload = (): ServiceIntegrationConfigRequest => ({
  baseUrl: fieldValue("mysql", "JDBC URL").trim(),
  username: fieldValue("mysql", "Username"),
  secret: fieldValue("mysql", "Password"),
  resource: fieldValue("mysql", "Database")
});

const rabbitMqPayload = (): ServiceIntegrationConfigRequest => ({
  baseUrl: fieldValue("rabbitmq", "AMQP URL").trim(),
  username: fieldValue("rabbitmq", "Username"),
  secret: fieldValue("rabbitmq", "Password"),
  resource: fieldValue("rabbitmq", "Virtual Host")
});

const springAiPayload = (): ReviewPolicyConfigRequest => ({
  llmEnabled: true,
  llmProvider: reverseProviderMap.value[fieldValue("spring-ai", "Provider")] ?? "dashscope",
  modelName: fieldValue("spring-ai", "Model").trim() || "qwen-plus",
  baseUrl: fieldValue("spring-ai", "Base URL"),
  apiKey: fieldValue("spring-ai", "API Key"),
  timeoutSeconds: reviewPolicyConfig.value?.timeoutSeconds ?? 60,
  temperature: reviewPolicyConfig.value?.temperature ?? 0.2,
  maxTokens: reviewPolicyConfig.value?.maxTokens ?? 4096,
  fallbackToRules: reviewPolicyConfig.value?.fallbackToRules ?? true,
  workerConcurrency: reviewPolicyConfig.value?.workerConcurrency ?? 1
});

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

const applyServiceConfig = (id: "mysql" | "rabbitmq", config: ServiceIntegrationConfig) => {
  const item = integrationItems.value.find((integration) => integration.id === id);
  if (!item) {
    return;
  }
  const isConfigured = config.status === "configured";
  const isFailed = config.status === "failed";
  const serviceName = id === "mysql" ? "MySQL" : "RabbitMQ";
  item.status = isConfigured ? "connected" : isFailed ? "failed" : "missing_secret";
  item.statusText = isConfigured ? "已连接" : isFailed ? "连接失败" : "未配置";
  item.metaLabel = config.lastCheckedAt ? "检测时间" : "更新时间";
  item.metaValue = config.lastCheckedAt ?? config.updatedAt ?? "未更新";
  item.message = config.lastError ?? (isConfigured
    ? `${serviceName} 检测配置已保存，不会切换当前运行连接`
    : `请配置用于检测的 ${serviceName} 连接信息`);
  item.diagnostics = [
    {
      label: "保存配置",
      value: serviceConfigStatusText(config.status),
      status: isConfigured ? "success" : isFailed ? "danger" : "warning"
    }
  ];
  item.fields = serviceFields(id, config);
  formState[id] = Object.fromEntries(item.fields.map((field) => [field.label, field.value]));
};

const serviceFields = (id: "mysql" | "rabbitmq", config: ServiceIntegrationConfig): IntegrationField[] => {
  if (id === "mysql") {
    return [
      { label: "JDBC URL", value: config.baseUrl ?? "", type: "text", placeholder: "jdbc:mysql://localhost:3306/repoguard" },
      { label: "Username", value: config.username ?? "", type: "text" },
      { label: "Password", value: config.secret ?? "", type: "password", placeholder: "MySQL password" },
      { label: "Database", value: config.resource ?? "", type: "text" }
    ];
  }
  return [
    { label: "AMQP URL", value: config.baseUrl ?? "", type: "text", placeholder: "amqp://localhost:5672" },
    { label: "Username", value: config.username ?? "", type: "text" },
    { label: "Password", value: config.secret ?? "", type: "password", placeholder: "RabbitMQ password" },
    { label: "Virtual Host", value: config.resource ?? "/", type: "text" }
  ];
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

const applyConnectionTestResult = (id: string, result: ConnectionTestResult) => {
  const item = integrationItems.value.find((integration) => integration.id === id);
  if (!item) {
    return;
  }
  item.status = result.success ? "connected" : "failed";
  item.statusText = result.success ? "已连接" : "连接失败";
  item.message = result.message;
  item.metaLabel = "检测时间";
  item.metaValue = result.checkedAt;
  if (id === "mysql" || id === "rabbitmq") {
    item.diagnostics = serviceDiagnostics(result);
  }
};

const serviceDiagnostics = (result: ConnectionTestResult): IntegrationDiagnosticItem[] => [
  {
    label: "检测来源",
    value: testedConfigSourceText(result.testedConfigSource),
    status: "info"
  },
  {
    label: "运行时",
    value: healthText(result.runtimeHealthy, result.runtimeConnectionStatus),
    status: healthStatus(result.runtimeHealthy)
  },
  {
    label: "保存配置",
    value: healthText(result.savedConfigHealthy, result.savedConfigStatus),
    status: healthStatus(result.savedConfigHealthy)
  },
  {
    label: "一致性",
    value: result.mismatch == null ? "未比较" : result.mismatch ? "不一致" : "一致",
    status: result.mismatch == null ? "info" : result.mismatch ? "warning" : "success"
  }
];

const testedConfigSourceText = (source?: string) => {
  switch (source) {
    case "submitted_config":
      return "当前表单";
    case "saved_config":
      return "保存配置";
    case "runtime_config":
      return "运行时配置";
    default:
      return "未标记";
  }
};

const healthText = (healthy?: boolean | null, status?: string | null) => {
  if (healthy == null) {
    return status === "not_configured" ? "未配置" : "不可用";
  }
  return healthy ? "健康" : "异常";
};

const healthStatus = (healthy?: boolean | null): IntegrationDiagnosticItem["status"] => {
  if (healthy == null) {
    return "info";
  }
  return healthy ? "success" : "danger";
};

const serviceConfigStatusText = (status: ServiceIntegrationConfig["status"]) => {
  switch (status) {
    case "configured":
      return "健康";
    case "failed":
      return "异常";
    default:
      return "未配置";
  }
};

onMounted(() => {
  void loadConfig();
});
</script>
