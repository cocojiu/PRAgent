<template>
  <div v-loading="loading" class="message-queue-page">
    <div class="page-heading page-heading-row">
      <div>
        <h1>消息队列健康状态</h1>
        <p>监控 RabbitMQ 发布、补偿、DLQ 与重试耗尽任务</p>
      </div>
      <div class="settings-actions">
        <el-switch v-model="autoRefresh" active-text="自动刷新" />
        <el-button :loading="loading" type="primary" plain @click="loadHealth">
          <RefreshCw :size="16" />
          刷新
        </el-button>
        <el-button @click="goIntegrations">
          <Settings :size="16" />
          跳转配置
        </el-button>
      </div>
    </div>

    <el-alert v-if="errorMessage" class="page-alert" type="error" :title="errorMessage" show-icon :closable="false" />

    <section v-if="health" class="mq-config-alert">
      <div class="mq-config-main">
        <Server :size="20" />
        <div>
          <strong>当前生效配置：{{ activeConfigTitle }}</strong>
          <span>{{ activeConfigMeta }}</span>
        </div>
      </div>
      <div class="mq-config-tags">
        <span :class="`status-pill ${savedConfigStatusClass}`">保存状态：{{ savedConfigStatusText }}</span>
        <span :class="`status-pill ${runtimeStatusClass}`">运行时：{{ runtimeStatusText }}</span>
        <span class="count-badge warning">测试连接不切换配置</span>
      </div>
    </section>

    <MetricGrid v-if="health" :metrics="metricItems" :resolve-icon="getMetricIcon" />

    <section v-if="health" class="mq-dashboard-grid">
      <article class="dashboard-card mq-topology-card">
        <div class="card-title-row">
          <h2>MQ链路状态</h2>
          <span class="count-badge">{{ health.activeConfig.configVersion }}</span>
        </div>
        <div class="mq-topology-meta">
          <span>Exchange：{{ health.topology.exchange }}</span>
          <span>Queue：{{ health.topology.queue }}</span>
          <span>DLQ：{{ health.topology.deadLetterQueue }}</span>
        </div>
        <div class="mq-flow">
          <div v-for="node in flowNodes" :key="node.label" class="mq-flow-node">
            <component :is="node.icon" :size="20" />
            <strong>{{ node.label }}</strong>
            <span :class="`status-pill ${node.statusClass}`">{{ node.status }}</span>
          </div>
        </div>
        <div class="mq-dlq-row">
          <span class="status-pill danger">DLQ</span>
          <span>{{ health.topology.deadLetterExchange }} / {{ health.topology.deadLetterRoutingKey }}</span>
        </div>
      </article>

      <article class="dashboard-card">
        <h2>配置与补偿</h2>
        <div class="health-list">
          <div class="health-item">
            <span>当前生效</span>
            <b>{{ health.activeConfig.baseUrl || "-" }}</b>
          </div>
          <div class="health-item">
            <span>最近连接检测</span>
            <b>{{ health.activeConfig.lastCheckedAt || "-" }}</b>
          </div>
          <div class="health-item">
            <span>最大重试</span>
            <b>{{ health.retryCompensation.maxAttempts }}次</b>
          </div>
          <div class="health-item">
            <span>补偿租约</span>
            <b>{{ formatMs(health.retryCompensation.leaseMs) }}</b>
          </div>
          <div class="health-item">
            <span>Claim中任务</span>
            <b>{{ health.retryCompensation.claimedTaskCount }}</b>
          </div>
        </div>
        <div class="health-footer">
          <span>切换 MQ 配置需在集成设置中保存后生效</span>
          <RouterLink :to="{ name: routeNames.integrations }">集成设置</RouterLink>
        </div>
      </article>
    </section>

    <section v-if="health" class="task-panel">
      <div class="filter-bar mq-filter-bar">
        <el-select v-model="statusFilter" placeholder="全部状态" clearable>
          <el-option label="全部状态" value="" />
          <el-option label="发布失败" value="PUBLISH_FAILED" />
          <el-option label="补偿中" value="PUBLISH_CLAIMED" />
          <el-option label="重试耗尽" value="RETRY_EXHAUSTED" />
          <el-option label="DLQ" value="DLQ" />
        </el-select>
        <el-select v-model="repositoryFilter" placeholder="全部仓库" clearable>
          <el-option label="全部仓库" value="" />
          <el-option v-for="repo in repositories" :key="repo" :label="repo" :value="repo" />
        </el-select>
        <el-input :model-value="health.activeConfig.configVersion" disabled />
        <el-input v-model="keyword" class="search-input" placeholder="搜索任务ID、仓库或错误原因" clearable>
          <template #suffix><Search :size="18" /></template>
        </el-input>
        <el-button type="primary" plain :loading="loading" @click="loadHealth">
          <RefreshCw :size="16" />
          刷新
        </el-button>
      </div>

      <el-table :data="filteredTasks" class="rg-table task-table" size="large" aria-label="消息队列异常任务列表">
        <el-table-column prop="taskId" label="任务ID" width="100" />
        <el-table-column label="仓库" min-width="170">
          <template #default="{ row }">
            <div class="repo-cell">
              <strong>{{ row.repository || "-" }}</strong>
              <span>{{ row.organization || "-" }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="PR" width="100">
          <template #default="{ row }">
            <span v-if="row.prNumber">#{{ row.prNumber }}</span>
            <span v-else class="muted-text">-</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="150">
          <template #default="{ row }">
            <span :class="`status-pill ${queueStatusClass(row.status)}`">{{ queueStatusText(row.status) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="publishAttempts" label="发布次数" width="100" />
        <el-table-column prop="nextRetryAt" label="下次重试时间" min-width="170">
          <template #default="{ row }">{{ row.nextRetryAt || "-" }}</template>
        </el-table-column>
        <el-table-column prop="claimedBy" label="Claim实例" min-width="150">
          <template #default="{ row }">{{ row.claimedBy || "-" }}</template>
        </el-table-column>
        <el-table-column label="最近错误" min-width="260">
          <template #default="{ row }">
            <span v-if="row.lastError" class="failure-summary-cell">
              <CircleAlert :size="15" />
              <span>{{ row.lastError }}</span>
            </span>
            <span v-else class="muted-text">-</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <div class="table-actions">
              <el-tooltip content="重新入队接口将在后续优化接入">
                <span>
                  <el-button size="small" :disabled="!canRequeue(row.status)">重新入队</el-button>
                </span>
              </el-tooltip>
              <RouterLink class="table-link" :to="{ name: routeNames.taskDetail, params: { id: row.taskId } }">查看</RouterLink>
            </div>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无消息队列异常任务" />
        </template>
      </el-table>

      <div class="table-footer">
        <span>共 {{ filteredTasks.length }} 条，生成于 {{ health.generatedAt }}</span>
        <span class="muted-text">数据源：{{ health.dataSource }}</span>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { RouterLink, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import {
  Boxes,
  CircleAlert,
  GitPullRequestArrow,
  ListChecks,
  RadioTower,
  RefreshCw,
  Search,
  Send,
  Server,
  Settings,
  Workflow
} from "lucide-vue-next";
import MetricGrid, { type MetricGridItem } from "@/components/MetricGrid.vue";
import { fetchMessageQueueHealth } from "@/api/messageQueue";
import { useMetricIcon } from "@/composables/useMetricIcon";
import { routeNames } from "@/router/names";
import type { MessageQueueExceptionTask, MessageQueueHealth, MessageQueueMetric } from "@/types";

const router = useRouter();
const loading = ref(false);
const errorMessage = ref("");
const health = ref<MessageQueueHealth>();
const statusFilter = ref("");
const repositoryFilter = ref("");
const keyword = ref("");
const autoRefresh = ref(false);
let refreshTimer: ReturnType<typeof setInterval> | undefined;

const metricIconMap = {
  blue: Send,
  red: CircleAlert,
  orange: RefreshCw,
  green: ListChecks,
  purple: Workflow
} as const;

const getMetricIcon = useMetricIcon(metricIconMap, Send);

const metricItems = computed<MetricGridItem[]>(() =>
  (health.value?.metrics ?? []).map((metric: MessageQueueMetric) => ({
    label: metricLabel(metric.label),
    value: metric.value,
    note: metricNote(metric.note),
    noteClass: metric.noteClass,
    color: metric.color
  }))
);

const flowNodes = computed(() => [
  { label: "API创建任务", icon: GitPullRequestArrow, status: "正常", statusClass: "success" },
  { label: "Exchange", icon: RadioTower, status: runtimeStatusText.value, statusClass: runtimeStatusClass.value },
  { label: "Review Queue", icon: Boxes, status: queueBacklogText.value, statusClass: queueBacklogClass.value },
  { label: "Worker消费", icon: Workflow, status: "正常", statusClass: "success" },
  { label: "LLM审查", icon: ListChecks, status: "正常", statusClass: "success" },
  { label: "回写结果", icon: Send, status: "正常", statusClass: "success" }
]);

const activeConfigTitle = computed(() => {
  const config = health.value?.activeConfig;
  if (!config) {
    return "-";
  }
  return `${config.baseUrl || "未配置"} · vhost ${config.virtualHost || "/"}`;
});

const activeConfigMeta = computed(() => {
  const config = health.value?.activeConfig;
  if (!config) {
    return "-";
  }
  return `配置版本 ${config.configVersion} · 已保存于 ${config.updatedAt || "-"}`;
});

const savedConfigStatusText = computed(() => configStatusText(health.value?.activeConfig.status));
const savedConfigStatusClass = computed(() => configStatusClass(health.value?.activeConfig.status));
const runtimeStatusText = computed(() => (health.value?.activeConfig.runtimeConnectionStatus === "CONNECTED" ? "已连接" : "未连接"));
const runtimeStatusClass = computed(() => (health.value?.activeConfig.runtimeConnectionStatus === "CONNECTED" ? "success" : "danger"));
const dlqBacklog = computed(() => Number(health.value?.metrics.find((metric) => metric.label === "DLQ backlog")?.value ?? 0));
const queueBacklogText = computed(() => (dlqBacklog.value > 0 ? "存在积压" : "堆积低"));
const queueBacklogClass = computed(() => (dlqBacklog.value > 0 ? "warning" : "success"));

const repositories = computed(() =>
  Array.from(new Set((health.value?.exceptionTasks ?? []).map((task) => task.repository).filter(Boolean) as string[])).sort()
);

const filteredTasks = computed(() => {
  const search = keyword.value.trim().toLowerCase();
  return (health.value?.exceptionTasks ?? []).filter((task) => {
    const matchesStatus = !statusFilter.value || task.status === statusFilter.value;
    const matchesRepo = !repositoryFilter.value || task.repository === repositoryFilter.value;
    const matchesKeyword = !search || [task.taskId, task.repository, task.organization, task.lastError, task.claimedBy]
      .filter((value) => value !== undefined && value !== null)
      .some((value) => String(value).toLowerCase().includes(search));
    return matchesStatus && matchesRepo && matchesKeyword;
  });
});

const loadHealth = async () => {
  loading.value = true;
  errorMessage.value = "";
  try {
    health.value = await fetchMessageQueueHealth();
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : "消息队列健康状态加载失败";
    ElMessage.error(errorMessage.value);
  } finally {
    loading.value = false;
  }
};

const goIntegrations = () => {
  router.push({ name: routeNames.integrations });
};

const formatMs = (value: number) => `${Math.round(value / 1000)}秒`;

const metricLabel = (label: string) => {
  const labels: Record<string, string> = {
    "Publish succeeded": "发布成功",
    "Publish failed": "发布失败",
    Compensating: "补偿中",
    "DLQ backlog": "DLQ积压"
  };
  return labels[label] ?? label;
};

const metricNote = (note: string) => {
  const notes: Record<string, string> = {
    "Current active config": "当前生效配置",
    "Waiting for compensation": "等待补偿",
    "Claimed by workers": "Worker已抢占",
    "Database observed status": "数据库观测状态"
  };
  return notes[note] ?? note;
};

const configStatusText = (status?: string) => {
  const labels: Record<string, string> = {
    CONNECTED: "已保存可用",
    CONFIGURED: "已配置",
    FAILED: "检测失败",
    NOT_CONFIGURED: "未配置"
  };
  return status ? labels[status] ?? status : "-";
};

const configStatusClass = (status?: string) => {
  if (status === "CONNECTED" || status === "CONFIGURED") {
    return "success";
  }
  if (status === "FAILED") {
    return "danger";
  }
  return "pending";
};

const queueStatusText = (status: string) => {
  const labels: Record<string, string> = {
    PUBLISH_FAILED: "发布失败",
    PUBLISH_CLAIMED: "补偿中",
    RETRY_EXHAUSTED: "重试耗尽",
    DLQ: "DLQ"
  };
  return labels[status] ?? status;
};

const queueStatusClass = (status: string) => {
  const classes: Record<string, string> = {
    PUBLISH_FAILED: "warning",
    PUBLISH_CLAIMED: "processing",
    RETRY_EXHAUSTED: "danger",
    DLQ: "danger"
  };
  return classes[status] ?? "pending";
};

const canRequeue = (status: MessageQueueExceptionTask["status"]) => status === "PUBLISH_FAILED" || status === "RETRY_EXHAUSTED";

const stopAutoRefresh = () => {
  if (refreshTimer) {
    clearInterval(refreshTimer);
    refreshTimer = undefined;
  }
};

watch(autoRefresh, (enabled) => {
  stopAutoRefresh();
  if (enabled) {
    refreshTimer = setInterval(() => {
      void loadHealth();
    }, 30000);
  }
});

onMounted(loadHealth);
onBeforeUnmount(stopAutoRefresh);
</script>
