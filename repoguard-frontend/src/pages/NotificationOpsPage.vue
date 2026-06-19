<template>
  <div v-loading="loading" class="notification-ops-page">
    <div class="page-heading page-heading-row">
      <div>
        <h1>通知运维</h1>
        <p>统一管理通知渠道、触发策略、事件投递与失败重试</p>
      </div>
      <div class="notification-actions">
        <el-button type="primary" size="large" :disabled="!canManage" @click="openBindingDialog()">
          <Plus :size="18" />
          新增绑定
        </el-button>
        <el-button type="primary" plain size="large" :disabled="!enabledNotificationBindings.length" @click="openTestDialog">
          <Send :size="17" />
          测试发送
        </el-button>
      </div>
    </div>

    <section class="notification-workbench">
      <el-tabs v-model="activeTab" class="notification-tabs">
        <el-tab-pane label="通知设置" name="settings">
          <div class="notification-settings-layout">
            <article class="notification-settings-card">
              <h2>触发策略</h2>
              <div class="switch-row">
                <span>GitHub 评论通知</span>
                <el-switch v-model="notificationForm.githubComment" :disabled="!canManage" @change="saveNotificationSettings" />
              </div>
              <div class="switch-row">
                <span>失败任务通知</span>
                <el-switch v-model="notificationForm.failedTask" :disabled="!canManage" @change="saveNotificationSettings" />
              </div>
              <div class="switch-row">
                <span>高风险 PR 通知</span>
                <el-switch v-model="notificationForm.highRiskPr" :disabled="!canManage" @change="saveNotificationSettings" />
              </div>
              <p class="settings-help-text">审查完成、人工复核、评论回写等细粒度事件可在渠道绑定中按仓库配置。</p>

              <h2 class="settings-section-title">默认通知范围</h2>
              <el-form label-position="top">
                <el-form-item label="接收对象">
                  <el-select v-model="recipientTarget" :disabled="!canManage">
                    <el-option label="项目成员" value="members" />
                    <el-option label="仓库管理员" value="maintainers" />
                  </el-select>
                </el-form-item>
                <el-form-item label="接收组">
                  <el-select v-model="recipientGroup" :disabled="!canManage">
                    <el-option label="运维组" value="ops" />
                    <el-option label="研发组" value="dev" />
                  </el-select>
                </el-form-item>
                <el-form-item label="免打扰时段">
                  <el-select v-model="quietHours" :disabled="!canManage">
                    <el-option label="不启用" value="off" />
                    <el-option label="22:00 - 08:00" value="night" />
                  </el-select>
                </el-form-item>
              </el-form>

              <h2 class="settings-section-title">失败重试</h2>
              <el-form label-position="top">
                <el-form-item label="最大重试次数">
                  <el-input-number v-model="maxRetryCount" :min="1" :max="10" :disabled="!canManage" />
                  <span class="form-tail">次</span>
                </el-form-item>
                <el-form-item label="重试间隔">
                  <el-select v-model="retryInterval" :disabled="!canManage">
                    <el-option label="5 分钟" value="5" />
                    <el-option label="15 分钟" value="15" />
                    <el-option label="30 分钟" value="30" />
                    <el-option label="60 分钟" value="60" />
                  </el-select>
                </el-form-item>
              </el-form>
              <div class="retry-chips">
                <button
                  v-for="item in retryIntervals"
                  :key="item"
                  type="button"
                  :class="{ active: retryInterval === item }"
                  @click="retryInterval = item"
                >
                  {{ item }} 分钟
                </button>
              </div>
            </article>

            <div class="notification-main-area">
              <div class="notification-metric-row">
                <article v-for="metric in metricItems" :key="metric.label" class="notification-metric-card">
                  <span :class="`metric-icon metric-icon--${metric.theme}`">
                    <component :is="metric.icon" :size="26" />
                  </span>
                  <div>
                    <p>{{ metric.label }}</p>
                    <strong>{{ metric.value }}</strong>
                  </div>
                </article>
              </div>

              <article class="notification-table-card">
                <div class="panel-heading notification-table-head">
                  <div>
                    <h2>最近通知事件</h2>
                  </div>
                  <el-button :loading="eventsLoading" @click="refreshNotificationData">
                    <RefreshCw :size="16" />
                  </el-button>
                </div>
                <el-table :data="notificationEvents" class="rg-table task-table" size="large">
                  <el-table-column label="事件类型" min-width="128">
                    <template #default="{ row }">{{ eventTypeText(row.eventType) }}</template>
                  </el-table-column>
                  <el-table-column label="目标任务" min-width="140">
                    <template #default="{ row }">PR-{{ row.taskId }} 代码审查</template>
                  </el-table-column>
                  <el-table-column label="渠道" min-width="126">
                    <template #default="{ row }">
                      <span class="channel-cell">
                        <component :is="channelIcon(row)" :size="18" />
                        {{ channelText(row) }}
                      </span>
                    </template>
                  </el-table-column>
                  <el-table-column label="状态" width="118">
                    <template #default="{ row }">
                      <span :class="`status-pill ${notificationStatusClass(row.status)}`">{{ notificationStatusText(row.status) }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="投递" width="136">
                    <template #default="{ row }">
                      <span class="delivery-summary">
                        {{ deliveryCountText(row) }}
                        <b v-if="row.deliverySummary?.failedDeliveryCount">失败 {{ row.deliverySummary.failedDeliveryCount }}</b>
                      </span>
                    </template>
                  </el-table-column>
                  <el-table-column label="最近投递" width="120">
                    <template #default="{ row }">
                      <span :class="`status-pill ${notificationStatusClass(row.deliverySummary?.latestDeliveryStatus || '')}`">
                        {{ notificationStatusText(row.deliverySummary?.latestDeliveryStatus || "") }}
                      </span>
                    </template>
                  </el-table-column>
                  <el-table-column label="最近错误" min-width="170" show-overflow-tooltip>
                    <template #default="{ row }">{{ row.lastError || "-" }}</template>
                  </el-table-column>
                  <el-table-column label="更新时间" min-width="168">
                    <template #default="{ row }">{{ row.updatedAt || row.createdAt || "-" }}</template>
                  </el-table-column>
                  <el-table-column label="操作" width="126" fixed="right">
                    <template #default="{ row }">
                      <el-button v-if="canRetryNotificationEvent(row.status)" link type="primary" :loading="retryingEventId === row.id" @click="retryEvent(row.id)">
                        重试
                      </el-button>
                      <el-button v-else link type="primary" @click="activeTab = 'events'">详情</el-button>
                    </template>
                  </el-table-column>
                  <template #empty>
                    <el-empty description="暂无通知事件" />
                  </template>
                </el-table>
                <div class="table-footer">
                  <span>共 {{ notificationEventTotal }} 条</span>
                  <el-pagination
                    v-model:current-page="eventFilter.page"
                    v-model:page-size="eventFilter.pageSize"
                    :page-sizes="[10, 20, 50]"
                    :total="notificationEventTotal"
                    layout="prev, pager, next, sizes"
                    @current-change="loadNotificationEvents"
                    @size-change="loadNotificationEvents"
                  />
                </div>
              </article>
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane label="渠道绑定" name="bindings">
          <article class="task-panel">
            <div class="panel-heading">
              <div>
                <h2>渠道绑定</h2>
                <p>按仓库绑定钉钉或企业微信群机器人，审查结果和评论回写会异步发送。</p>
              </div>
              <el-button type="primary" :disabled="!canManage" @click="openBindingDialog()">新增绑定</el-button>
            </div>
            <NotificationBindingTable
              :bindings="notificationBindings"
              :can-manage="canManage"
              :loading="bindingsLoading"
              :testing-binding-id="testingBindingId"
              action-layout="group"
              table-class="rg-table task-table"
              size="large"
              @edit="openBindingDialog"
              @remove="removeBinding"
              @test="runBindingTest"
              @toggle="toggleBinding"
            />
          </article>
        </el-tab-pane>

        <el-tab-pane label="通知事件" name="events">
          <article class="task-panel">
            <div class="filter-bar notification-filter-bar">
              <el-select v-model="eventFilter.status" placeholder="全部状态" clearable>
                <el-option label="PENDING" value="PENDING" />
                <el-option label="PUBLISHED" value="PUBLISHED" />
                <el-option label="DELIVERING" value="DELIVERING" />
                <el-option label="DELIVERED" value="DELIVERED" />
                <el-option label="PUBLISH_FAILED" value="PUBLISH_FAILED" />
                <el-option label="DELIVERY_FAILED" value="DELIVERY_FAILED" />
                <el-option label="DEAD" value="DEAD" />
              </el-select>
              <el-input-number v-model="eventFilter.taskId" :min="1" :controls="false" placeholder="Task ID" />
              <el-button type="primary" plain :loading="eventsLoading" @click="loadNotificationEvents">
                <RefreshCw :size="16" />
                刷新
              </el-button>
            </div>
            <el-table :data="notificationEvents" class="rg-table task-table" size="large">
              <el-table-column prop="id" label="ID" width="86" />
              <el-table-column label="事件类型" min-width="190">
                <template #default="{ row }">{{ eventTypeText(row.eventType) }}</template>
              </el-table-column>
              <el-table-column prop="taskId" label="任务" width="100" />
              <el-table-column label="状态" width="150">
                <template #default="{ row }">
                  <span :class="`status-pill ${notificationStatusClass(row.status)}`">{{ notificationStatusText(row.status) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="渠道" min-width="150">
                <template #default="{ row }">
                  <span class="channel-cell">
                    <component :is="channelIcon(row)" :size="18" />
                    {{ channelText(row) }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column label="投递数" width="96">
                <template #default="{ row }">{{ row.deliverySummary?.deliveryCount ?? 0 }}</template>
              </el-table-column>
              <el-table-column label="失败投递" width="110">
                <template #default="{ row }">
                  <span :class="{ 'danger-count': (row.deliverySummary?.failedDeliveryCount ?? 0) > 0 }">
                    {{ row.deliverySummary?.failedDeliveryCount ?? 0 }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column label="最近投递状态" width="140">
                <template #default="{ row }">
                  <span :class="`status-pill ${notificationStatusClass(row.deliverySummary?.latestDeliveryStatus || '')}`">
                    {{ notificationStatusText(row.deliverySummary?.latestDeliveryStatus || "") }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column prop="retryCount" label="重试" width="86" />
              <el-table-column prop="nextRetryAt" label="下次重试" min-width="160" />
              <el-table-column prop="lastError" label="最近错误" min-width="240" show-overflow-tooltip />
              <el-table-column label="操作" width="120" fixed="right">
                <template #default="{ row }">
                  <el-button size="small" :disabled="!canManage || !canRetryNotificationEvent(row.status)" :loading="retryingEventId === row.id" @click="retryEvent(row.id)">
                    重试
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
          </article>
        </el-tab-pane>

        <el-tab-pane label="投递记录" name="deliveries">
          <article class="task-panel">
            <div class="filter-bar notification-filter-bar">
              <el-select v-model="deliveryFilter.status" placeholder="全部状态" clearable>
                <el-option label="SUCCESS" value="SUCCESS" />
                <el-option label="FAILED" value="FAILED" />
                <el-option label="SKIPPED" value="SKIPPED" />
              </el-select>
              <el-input-number v-model="deliveryFilter.taskId" :min="1" :controls="false" placeholder="Task ID" />
              <el-button type="primary" plain :loading="deliveriesLoading" @click="loadNotificationDeliveries">
                <RefreshCw :size="16" />
                刷新
              </el-button>
            </div>
            <el-table :data="notificationDeliveries" class="rg-table task-table" size="large">
              <el-table-column prop="id" label="ID" width="86" />
              <el-table-column prop="eventId" label="事件" width="96" />
              <el-table-column prop="bindingId" label="绑定" width="96" />
              <el-table-column prop="taskId" label="任务" width="96" />
              <el-table-column label="平台" width="120">
                <template #default="{ row }">{{ providerText(row.provider) }}</template>
              </el-table-column>
              <el-table-column label="状态" width="130">
                <template #default="{ row }">
                  <span :class="`status-pill ${notificationStatusClass(row.status)}`">{{ notificationStatusText(row.status) }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="attemptCount" label="次数" width="86" />
              <el-table-column prop="failureReason" label="失败原因" min-width="240" show-overflow-tooltip />
              <el-table-column prop="sentAt" label="发送时间" min-width="160" />
            </el-table>
          </article>
        </el-tab-pane>
      </el-tabs>
    </section>

    <NotificationBindingDialog
      v-model:visible="bindingDialogVisible"
      :can-manage="canManage"
      :editing-binding-id="editingBindingId"
      :form="bindingForm"
      :saving="savingBinding"
      @save="saveBinding"
    />

    <el-dialog v-model="testDialogVisible" title="测试发送" width="520px">
      <el-form label-width="96px">
        <el-form-item label="通知渠道">
          <el-select v-model="selectedTestBindingId" placeholder="请选择要测试的渠道绑定">
            <el-option
              v-for="binding in enabledNotificationBindings"
              :key="binding.id"
              :label="`${binding.name} · ${providerText(binding.provider)} · ${binding.organization}/${binding.repository}`"
              :value="binding.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="测试说明">
          <p class="test-dialog-note">将调用该绑定的测试接口，向对应机器人发送一条连接测试消息。</p>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="testDialogVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!selectedTestBindingId" :loading="testingBindingId === selectedTestBindingId" @click="runSelectedBindingTest">
          发送测试
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import {
  Plus,
  RefreshCw,
  Send
} from "lucide-vue-next";
import {
  buildNotificationMetricItems,
  canRetryNotificationEvent,
  channelIcon,
  channelText,
  deliveryCountText,
  eventTypeText,
  NotificationBindingDialog,
  NotificationBindingTable,
  notificationStatusClass,
  notificationStatusText,
  providerText,
  useNotificationBindings,
  useNotificationOpsRecords,
  useNotificationOpsSettings,
  useNotificationOpsTestDialog
} from "@/features/notification-ops";
import { canManage } from "@/stores/authState";

const activeTab = ref("settings");
const loading = ref(false);
const recipientTarget = ref("members");
const recipientGroup = ref("ops");
const quietHours = ref("off");
const maxRetryCount = ref(5);
const retryInterval = ref("5");
const retryIntervals = ["1", "5", "15", "30", "60"];

const {
  notificationBindings,
  bindingsLoading,
  bindingDialogVisible,
  savingBinding,
  testingBindingId,
  editingBindingId,
  bindingForm,
  loadNotificationBindings,
  openBindingDialog,
  saveBinding,
  runBindingTest,
  toggleBinding,
  removeBinding
} = useNotificationBindings();
const {
  deliveriesLoading,
  deliveryFilter,
  eventFilter,
  eventsLoading,
  notificationDeliveries,
  notificationEvents,
  notificationEventTotal,
  retryingEventId,
  loadNotificationDeliveries,
  loadNotificationEvents,
  refreshNotificationData,
  retryEvent
} = useNotificationOpsRecords({ loadNotificationBindings });
const {
  notificationForm,
  loadSystemSettings,
  saveNotificationSettings
} = useNotificationOpsSettings({ canManage });
const {
  enabledNotificationBindings,
  selectedTestBindingId,
  testDialogVisible,
  openTestDialog,
  runSelectedBindingTest
} = useNotificationOpsTestDialog({
  notificationBindings,
  runBindingTest
});

const metricItems = computed(() =>
  buildNotificationMetricItems({
    notificationBindings: notificationBindings.value,
    notificationEvents: notificationEvents.value,
    notificationEventTotal: notificationEventTotal.value
  })
);
const loadPage = async () => {
  loading.value = true;
  try {
    await Promise.all([loadSystemSettings(), refreshNotificationData()]);
  } finally {
    loading.value = false;
  }
};

onMounted(() => {
  void loadPage();
});
</script>

<style scoped>
.notification-ops-page {
  display: flex;
  flex-direction: column;
  gap: 22px;
}

.notification-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.notification-workbench {
  border: 1px solid #e5eaf3;
  border-radius: 8px;
  background: #ffffff;
  box-shadow: 0 2px 10px rgba(15, 23, 42, 0.04);
}

.notification-tabs {
  padding: 0 22px 22px;
}

.notification-tabs :deep(.el-tabs__header) {
  margin-bottom: 18px;
}

.notification-settings-layout {
  display: grid;
  grid-template-columns: 420px minmax(0, 1fr);
  gap: 20px;
}

.notification-settings-card,
.notification-table-card {
  border: 1px solid #e5eaf3;
  border-radius: 8px;
  background: #ffffff;
}

.notification-settings-card {
  padding: 20px 22px;
}

.notification-settings-card h2,
.notification-table-card h2 {
  margin: 0;
  color: #0f172a;
  font-size: 20px;
}

.settings-section-title {
  margin-top: 22px !important;
  padding-top: 18px;
  border-top: 1px solid #eef2f7;
}

.settings-help-text,
.test-dialog-note {
  margin: 12px 0 0;
  color: #64748b;
  line-height: 1.7;
}

.test-dialog-note {
  margin: 0;
}

.notification-settings-card :deep(.el-select),
.notification-settings-card :deep(.el-input-number) {
  width: 100%;
}

.notification-settings-card :deep(.el-form-item) {
  margin-bottom: 12px;
}

.form-tail {
  margin-left: 8px;
  color: #64748b;
}

.retry-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.retry-chips button {
  height: 34px;
  padding: 0 12px;
  border: 1px solid #d9e2ec;
  border-radius: 6px;
  background: #ffffff;
  color: #475569;
  cursor: pointer;
}

.retry-chips button.active {
  border-color: #1268ff;
  color: #1268ff;
  background: #eaf2ff;
}

.notification-main-area {
  display: flex;
  flex-direction: column;
  gap: 18px;
  min-width: 0;
}

.notification-metric-row {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
}

.notification-metric-card {
  min-height: 112px;
  display: flex;
  align-items: center;
  gap: 18px;
  padding: 20px 22px;
  border: 1px solid #e5eaf3;
  border-radius: 8px;
  background: #ffffff;
  box-shadow: 0 2px 10px rgba(15, 23, 42, 0.04);
}

.notification-metric-card p {
  margin: 0 0 8px;
  color: #334155;
  font-size: 16px;
  font-weight: 700;
}

.notification-metric-card strong {
  color: #0f172a;
  font-size: 30px;
  line-height: 1;
}

.notification-table-card {
  overflow: hidden;
}

.notification-table-head {
  padding: 16px 18px;
}

.channel-cell {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: #334155;
}

.channel-cell svg {
  color: #1268ff;
}

.delivery-summary {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: #334155;
  font-weight: 700;
}

.delivery-summary b {
  color: #dc2626;
  font-size: 12px;
}

.notification-filter-bar {
  grid-template-columns: 180px 180px auto minmax(0, 1fr);
}

@media (max-width: 1280px) {
  .notification-settings-layout {
    grid-template-columns: minmax(0, 1fr);
  }

  .notification-metric-row {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 760px) {
  .notification-actions,
  .notification-table-head {
    align-items: flex-start;
    flex-direction: column;
  }

  .notification-metric-row,
  .notification-filter-bar {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
