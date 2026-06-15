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

    <section class="notification-ops">
      <div class="notification-bindings__head">
        <div>
          <h2>通知事件运维</h2>
          <p>查看 Outbox 事件发布状态和第三方平台投递结果，异常事件可手动重试。</p>
        </div>
        <el-button :loading="notificationOpsLoading" @click="refreshNotificationOps">
          <RefreshCw :size="16" />
          刷新
        </el-button>
      </div>

      <el-tabs v-model="notificationOpsTab">
        <el-tab-pane label="通知事件" name="events">
          <div class="notification-ops__filter">
            <el-select v-model="eventFilter.status" clearable placeholder="事件状态">
              <el-option label="PENDING" value="PENDING" />
              <el-option label="PUBLISHED" value="PUBLISHED" />
              <el-option label="DELIVERING" value="DELIVERING" />
              <el-option label="DELIVERED" value="DELIVERED" />
              <el-option label="PUBLISH_FAILED" value="PUBLISH_FAILED" />
              <el-option label="DELIVERY_FAILED" value="DELIVERY_FAILED" />
              <el-option label="DEAD" value="DEAD" />
            </el-select>
            <el-input-number v-model="eventFilter.taskId" :min="1" :controls="false" placeholder="Task ID" />
            <el-button @click="loadNotificationEvents">查询</el-button>
          </div>
          <el-table :data="notificationEvents" border>
            <el-table-column prop="id" label="ID" width="86" />
            <el-table-column prop="eventType" label="事件类型" min-width="190" show-overflow-tooltip />
            <el-table-column prop="taskId" label="任务" width="96" />
            <el-table-column label="状态" width="150">
              <template #default="{ row }">
                <el-tag :type="notificationStatusType(row.status)">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="retryCount" label="重试" width="86" />
            <el-table-column prop="nextRetryAt" label="下次重试" min-width="160" />
            <el-table-column prop="lastError" label="最近错误" min-width="240" show-overflow-tooltip />
            <el-table-column label="操作" width="120" fixed="right">
              <template #default="{ row }">
                <el-button size="small" :disabled="!canManage" :loading="retryingEventId === row.id" @click="retryEvent(row.id)">
                  重试
                </el-button>
              </template>
            </el-table-column>
          </el-table>
          <div class="notification-ops__footer">
            <span>共 {{ notificationEventTotal }} 条</span>
            <el-pagination
              v-model:current-page="eventFilter.page"
              :page-size="eventFilter.pageSize"
              :total="notificationEventTotal"
              layout="prev, pager, next"
              @current-change="loadNotificationEvents"
            />
          </div>
        </el-tab-pane>

        <el-tab-pane label="投递记录" name="deliveries">
          <div class="notification-ops__filter">
            <el-select v-model="deliveryFilter.status" clearable placeholder="投递状态">
              <el-option label="SUCCESS" value="SUCCESS" />
              <el-option label="FAILED" value="FAILED" />
              <el-option label="SKIPPED" value="SKIPPED" />
            </el-select>
            <el-input-number v-model="deliveryFilter.taskId" :min="1" :controls="false" placeholder="Task ID" />
            <el-button @click="loadNotificationDeliveries">查询</el-button>
          </div>
          <el-table :data="notificationDeliveries" border>
            <el-table-column prop="id" label="ID" width="86" />
            <el-table-column prop="eventId" label="事件" width="96" />
            <el-table-column prop="bindingId" label="绑定" width="96" />
            <el-table-column prop="taskId" label="任务" width="96" />
            <el-table-column label="平台" width="120">
              <template #default="{ row }">{{ providerText(row.provider) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="130">
              <template #default="{ row }">
                <el-tag :type="notificationStatusType(row.status)">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="attemptCount" label="次数" width="86" />
            <el-table-column prop="failureReason" label="失败原因" min-width="240" show-overflow-tooltip />
            <el-table-column prop="sentAt" label="发送时间" min-width="160" />
          </el-table>
          <div class="notification-ops__footer">
            <span>共 {{ notificationDeliveryTotal }} 条</span>
            <el-pagination
              v-model:current-page="deliveryFilter.page"
              :page-size="deliveryFilter.pageSize"
              :total="notificationDeliveryTotal"
              layout="prev, pager, next"
              @current-change="loadNotificationDeliveries"
            />
          </div>
        </el-tab-pane>
      </el-tabs>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import { Database, Github, Hexagon, RadioTower, RefreshCw, Save } from "lucide-vue-next";
import type { Component } from "vue";
import { canManage } from "@/stores/authState";
import {
  fetchGithubIntegrationConfig,
  fetchMysqlIntegrationConfig,
  fetchNotificationDeliveries,
  fetchNotificationEvents,
  fetchRabbitMqIntegrationConfig,
  fetchReviewPolicyConfig,
  retryNotificationEvent,
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
  NotificationDelivery,
  NotificationEvent,
  ReviewPolicyConfig,
  ReviewPolicyConfigRequest,
  ServiceIntegrationConfig,
  ServiceIntegrationConfigRequest
} from "@/types";
import { getErrorMessage } from "@/utils/errors";

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
      { label: "Base URL", value: "https://dashscope.aliyuncs.com/compatible-mode/v1", type: "text" },
      { label: "Chunk File Threshold", value: "6", type: "text" },
      { label: "Chunk Line Threshold", value: "700", type: "text" },
      { label: "Chunk Max Files", value: "4", type: "text" },
      { label: "Chunk Max Lines", value: "450", type: "text" },
      { label: "Input $/1M Tokens", value: "0", type: "text" },
      { label: "Output $/1M Tokens", value: "0", type: "text" }
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
const notificationOpsTab = ref("events");
const notificationOpsLoading = ref(false);
const retryingEventId = ref<number>();
const notificationEvents = ref<NotificationEvent[]>([]);
const notificationDeliveries = ref<NotificationDelivery[]>([]);
const notificationEventTotal = ref(0);
const notificationDeliveryTotal = ref(0);
const eventFilter = reactive<{ page: number; pageSize: number; status?: string; taskId?: number }>({
  page: 1,
  pageSize: 10,
  status: undefined,
  taskId: undefined
});
const deliveryFilter = reactive<{ page: number; pageSize: number; status?: string; taskId?: number }>({
  page: 1,
  pageSize: 10,
  status: undefined,
  taskId: undefined
});
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
  if (testingConnections[id]) {
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
    const message = getErrorMessage(error, "Connection test failed");
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
    const [github, mysql, rabbitMq, reviewPolicy, events, deliveries] = await Promise.all([
      fetchGithubIntegrationConfig(),
      fetchMysqlIntegrationConfig(),
      fetchRabbitMqIntegrationConfig(),
      fetchReviewPolicyConfig(),
      fetchNotificationEvents({ page: eventFilter.page, pageSize: eventFilter.pageSize }),
      fetchNotificationDeliveries({ page: deliveryFilter.page, pageSize: deliveryFilter.pageSize })
    ]);
    githubConfig.value = github;
    mysqlConfig.value = mysql;
    rabbitMqConfig.value = rabbitMq;
    reviewPolicyConfig.value = reviewPolicy;
    applyGithubConfig(github);
    applyServiceConfig("mysql", mysql);
    applyServiceConfig("rabbitmq", rabbitMq);
    applyReviewPolicyConfig(reviewPolicy);
    notificationEvents.value = events.items;
    notificationEventTotal.value = events.total;
    notificationDeliveries.value = deliveries.items;
    notificationDeliveryTotal.value = deliveries.total;
  } catch (error) {
    ElMessage.warning(getErrorMessage(error, "Config load failed, using local defaults"));
  } finally {
    loading.value = false;
  }
};

const loadNotificationEvents = async () => {
  const result = await fetchNotificationEvents({
    page: eventFilter.page,
    pageSize: eventFilter.pageSize,
    status: eventFilter.status,
    taskId: eventFilter.taskId
  });
  notificationEvents.value = result.items;
  notificationEventTotal.value = result.total;
};

const loadNotificationDeliveries = async () => {
  const result = await fetchNotificationDeliveries({
    page: deliveryFilter.page,
    pageSize: deliveryFilter.pageSize,
    status: deliveryFilter.status,
    taskId: deliveryFilter.taskId
  });
  notificationDeliveries.value = result.items;
  notificationDeliveryTotal.value = result.total;
};

const refreshNotificationOps = async () => {
  if (notificationOpsLoading.value) {
    return;
  }
  notificationOpsLoading.value = true;
  try {
    await Promise.all([loadNotificationEvents(), loadNotificationDeliveries()]);
  } catch (error) {
    ElMessage.error(getErrorMessage(error, "通知事件刷新失败"));
  } finally {
    notificationOpsLoading.value = false;
  }
};

const retryEvent = async (id: number) => {
  if (retryingEventId.value) {
    return;
  }
  retryingEventId.value = id;
  try {
    const updated = await retryNotificationEvent(id);
    const index = notificationEvents.value.findIndex((event) => event.id === updated.id);
    if (index >= 0) {
      notificationEvents.value[index] = updated;
    }
    ElMessage.success("通知事件已重新入队");
    await refreshNotificationOps();
  } catch (error) {
    ElMessage.error(getErrorMessage(error, "通知事件重试失败"));
  } finally {
    retryingEventId.value = undefined;
  }
};

const notificationStatusType = (status: string) => {
  const normalized = status?.toUpperCase();
  if (["DELIVERED", "SUCCESS", "PUBLISHED"].includes(normalized)) {
    return "success";
  }
  if (["PENDING", "DELIVERING", "SKIPPED"].includes(normalized)) {
    return "info";
  }
  if (["PUBLISH_FAILED", "DELIVERY_FAILED", "FAILED", "DEAD"].includes(normalized)) {
    return "danger";
  }
  return "warning";
};

const providerText = (provider: string) => {
  if (provider === "DINGTALK") {
    return "钉钉";
  }
  if (provider === "WECOM") {
    return "企业微信";
  }
  return provider;
};

const saveConfig = async () => {
  if (!canManage.value || saving.value) {
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
    ElMessage.error(getErrorMessage(error, "Config save failed"));
  } finally {
    saving.value = false;
  }
};

const fieldValue = (id: IntegrationId, label: string) => formState[id]?.[label] ?? "";

const numberFieldValue = (id: IntegrationId, label: string, fallback: number) => {
  const parsed = Number(fieldValue(id, label));
  return Number.isFinite(parsed) ? parsed : fallback;
};

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
  workerConcurrency: reviewPolicyConfig.value?.workerConcurrency ?? 1,
  chunkFileThreshold: numberFieldValue("spring-ai", "Chunk File Threshold", reviewPolicyConfig.value?.chunkFileThreshold ?? 6),
  chunkLineThreshold: numberFieldValue("spring-ai", "Chunk Line Threshold", reviewPolicyConfig.value?.chunkLineThreshold ?? 700),
  chunkMaxFiles: numberFieldValue("spring-ai", "Chunk Max Files", reviewPolicyConfig.value?.chunkMaxFiles ?? 4),
  chunkMaxLines: numberFieldValue("spring-ai", "Chunk Max Lines", reviewPolicyConfig.value?.chunkMaxLines ?? 450),
  inputTokenPricePerMillion: numberFieldValue(
    "spring-ai",
    "Input $/1M Tokens",
    reviewPolicyConfig.value?.inputTokenPricePerMillion ?? 0
  ),
  outputTokenPricePerMillion: numberFieldValue(
    "spring-ai",
    "Output $/1M Tokens",
    reviewPolicyConfig.value?.outputTokenPricePerMillion ?? 0
  )
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
    { label: "Base URL", value: config.baseUrl ?? "", type: "text" },
    { label: "Chunk File Threshold", value: String(config.chunkFileThreshold ?? 6), type: "text" },
    { label: "Chunk Line Threshold", value: String(config.chunkLineThreshold ?? 700), type: "text" },
    { label: "Chunk Max Files", value: String(config.chunkMaxFiles ?? 4), type: "text" },
    { label: "Chunk Max Lines", value: String(config.chunkMaxLines ?? 450), type: "text" },
    { label: "Input $/1M Tokens", value: String(config.inputTokenPricePerMillion ?? 0), type: "text" },
    { label: "Output $/1M Tokens", value: String(config.outputTokenPricePerMillion ?? 0), type: "text" }
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
