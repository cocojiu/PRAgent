<template>
  <section class="dashboard-card model-release-center-card">
    <div class="policy-governance-heading">
      <div>
        <h2>LLM 模型发布中心</h2>
        <p>发布只消费服务端不可变评估报告；新任务按当前发布路由，已开始任务保留原模型分配。</p>
      </div>
      <div class="release-center-actions">
        <el-select v-model="trendDays" aria-label="质量趋势窗口" @change="load">
          <el-option :value="7" label="最近 7 天" />
          <el-option :value="30" label="最近 30 天" />
          <el-option :value="90" label="最近 90 天" />
        </el-select>
        <el-button :loading="loading" @click="load">刷新发布状态</el-button>
      </div>
    </div>

    <el-alert
      v-if="errorMessage"
      class="page-alert"
      type="error"
      :title="errorMessage"
      show-icon
      :closable="false"
    />

    <template v-if="center">
      <div class="release-center-metric-grid">
        <div>
          <span>当前配置</span>
          <strong>{{ center.configuredProvider || "未配置" }} / {{ center.configuredModel || "未配置" }}</strong>
        </div>
        <div>
          <span>Active</span>
          <strong>{{ center.activeRelease?.modelName || "未发布" }}</strong>
          <small>{{ center.activeRelease ? center.activeRelease.releaseKey : "先运行评估" }}</small>
        </div>
        <div>
          <span>Canary</span>
          <strong>{{ center.canaryRelease?.modelName || "无" }}</strong>
          <small v-if="center.canaryRelease">流量 {{ center.canaryRelease.trafficPercent }}%</small>
          <small v-else>高级能力已折叠</small>
        </div>
        <div>
          <span>本月预算</span>
          <strong>{{ budgetText(center.monthlyBudget) }}</strong>
          <small>{{ center.monthlyBudget.exhausted ? "已触发保护" : `剩余 ${tokenText(center.monthlyBudget.tokenRemaining)}` }}</small>
        </div>
      </div>

      <el-alert
        class="release-center-recommendation"
        type="info"
        :title="`建议：${center.recommendedAction}`"
        :closable="false"
      />

      <el-collapse v-model="advancedOpen" class="release-center-advanced">
        <el-collapse-item name="advanced" title="灰度与发布（高级）">
          <el-form inline class="release-center-form" @submit.prevent>
            <el-form-item label="发布键">
              <el-input v-model="releaseKey" :disabled="!canManage" placeholder="例如 gpt-next-2026-09" />
            </el-form-item>
            <el-form-item label="服务端评估报告">
              <el-select
                v-model="selectedReportId"
                :disabled="!canManage || !reports.length"
                placeholder="选择报告"
                class="release-report-select"
              >
                <el-option
                  v-for="report in reports"
                  :key="report.id"
                  :value="report.id"
                  :disabled="report.status !== 'COMPLETED'"
                  :label="`${report.status === 'PROVISIONAL' ? '[小样本] ' : ''}#${report.id} · ${report.provider}/${report.model} · ${report.datasetId}@${report.datasetVersion}`"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="Canary 流量">
              <el-input-number v-model="canaryTraffic" :min="1" :max="100" :disabled="!canManage" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :disabled="!canManage || !selectedReport || selectedReport.status !== 'COMPLETED'" :loading="action === 'shadow'" @click="registerShadow">
                注册 Shadow
              </el-button>
              <el-button type="success" :disabled="!canManage || !selectedReport || selectedReport.status !== 'COMPLETED'" :loading="action.startsWith('promote-')" @click="promote()">
                发布 Canary
              </el-button>
            </el-form-item>
          </el-form>
          <el-alert
            v-if="selectedReport"
            class="release-report-evidence"
            :type="selectedReport.eligible && !selectedReport.blockers.length ? 'success' : 'warning'"
            :title="selectedReport.status === 'PROVISIONAL' ? '小样本报告不可用于模型发布' : selectedReport.blockers.length ? `报告阻断：${selectedReport.blockers.join('；')}` : '指标由服务端报告提供，客户端不可修改'"
            :closable="false"
          >
            <template #default>
              <span>Precision {{ percent(selectedReport.precision) }} · Recall {{ percent(selectedReport.recall) }} · P95 {{ selectedReport.metrics.p95LatencyMs }} ms</span>
            </template>
          </el-alert>
        </el-collapse-item>
      </el-collapse>

      <el-table :data="center.releases" class="rg-table release-table" size="small" row-key="id" aria-label="模型发布记录">
        <el-table-column label="版本" min-width="205">
          <template #default="{ row }">
            <div class="release-version-cell">
              <strong>{{ row.releaseKey }}</strong>
              <span>{{ row.provider }} / {{ row.modelName }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="105">
          <template #default="{ row }"><el-tag :type="stateType(row.state)">{{ stateText(row.state) }}</el-tag></template>
        </el-table-column>
        <el-table-column label="流量" width="90"><template #default="{ row }">{{ row.trafficPercent }}%</template></el-table-column>
        <el-table-column label="评估证据" min-width="160">
          <template #default="{ row }">{{ row.evaluationReportId ? `报告 #${row.evaluationReportId}` : "未绑定" }}</template>
        </el-table-column>
        <el-table-column label="操作者 / 时间" min-width="190">
          <template #default="{ row }"><div class="release-version-cell"><span>{{ row.createdBy }}</span><small>{{ row.updatedAt || row.createdAt || "—" }}</small></div></template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.state === 'SHADOW' || row.state === 'CANARY'"
              size="small"
              type="success"
              plain
              :disabled="!canManage || !row.evaluationReportId"
              :loading="action === `promote-${row.releaseKey}`"
              @click="promote(row)"
            >{{ row.state === "CANARY" ? "更新流量" : "发布 Canary" }}</el-button>
            <el-button
              v-if="row.state !== 'ROLLED_BACK'"
              size="small"
              type="danger"
              plain
              :disabled="!canManage"
              :loading="action === `rollback-${row.id}`"
              @click="requestRollback(row.id)"
            >回滚</el-button>
          </template>
        </el-table-column>
        <template #empty><el-empty description="暂无模型发布记录" /></template>
      </el-table>

      <div class="release-center-subheading">
        <div><strong>最近质量趋势</strong><span>按当前窗口汇总，不替代评估报告准入证据</span></div>
      </div>
      <el-table :data="center.modelComparison" class="rg-table release-comparison-table" size="small" aria-label="模型质量趋势">
        <el-table-column prop="model" label="模型" min-width="180" />
        <el-table-column prop="taskCount" label="任务数" width="90" />
        <el-table-column prop="averageDuration" label="平均耗时" width="110" />
        <el-table-column prop="averageTokens" label="平均 Token" width="120" />
        <el-table-column prop="averageCost" label="平均费用" width="110" />
        <el-table-column prop="parseSuccessRate" label="解析成功" width="110" />
        <el-table-column prop="fallbackRate" label="Fallback" width="100" />
        <template #empty><el-empty description="暂无趋势数据" /></template>
      </el-table>

      <div class="release-center-subheading">
        <div><strong>发布运行指标与告警</strong><span>仅展示聚合指标；样本不足时不会触发告警或自动回滚</span></div>
      </div>
      <el-table :data="runtimeMetrics" class="rg-table release-runtime-metrics-table" size="small" aria-label="模型发布运行指标">
        <el-table-column label="版本" min-width="180">
          <template #default="{ row }">
            <div class="release-version-cell">
              <strong>{{ row.releaseKey }}</strong>
              <span>{{ row.provider }} / {{ row.modelName }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="窗口" min-width="165">
          <template #default="{ row }">{{ row.windowStart }} ~ {{ row.windowEnd }}</template>
        </el-table-column>
        <el-table-column label="状态" width="125">
          <template #default="{ row }"><el-tag :type="runtimeStateType(row.alertState)">{{ runtimeStateText(row.alertState) }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="sampleCount" label="样本" width="75" />
        <el-table-column label="P95" width="90"><template #default="{ row }">{{ row.p95LatencyMs }} ms</template></el-table-column>
        <el-table-column label="解析失败" width="105"><template #default="{ row }">{{ rate(row.parseFailureCount, row.sampleCount) }}</template></el-table-column>
        <el-table-column label="Fallback" width="95"><template #default="{ row }">{{ rate(row.fallbackCount, row.sampleCount) }}</template></el-table-column>
        <el-table-column label="动作" width="115"><template #default="{ row }">{{ runtimeActionText(row.action) }}</template></el-table-column>
        <el-table-column label="原因" min-width="240">
          <template #default="{ row }">{{ row.alertCodes.length ? row.alertCodes.join("；") : "—" }}</template>
        </el-table-column>
        <template #empty><el-empty description="暂无运行指标" /></template>
      </el-table>

      <el-collapse v-model="auditOpen" class="release-center-advanced release-audit-panel">
        <el-collapse-item name="audit" title="发布审计时间线与完整性（高级）">
          <div class="release-audit-toolbar">
            <el-input v-model="auditOperator" clearable placeholder="按操作者筛选" aria-label="审计操作者" />
            <el-select v-model="auditFilterAction" clearable placeholder="全部动作" aria-label="审计动作">
              <el-option value="REGISTER_SHADOW" label="注册 Shadow" />
              <el-option value="PROMOTE" label="发布 / 更新" />
              <el-option value="REPLACE_ACTIVE" label="替换 Active" />
              <el-option value="ROLLBACK" label="人工回滚" />
              <el-option value="AUTO_ROLLBACK" label="自动回滚" />
            </el-select>
            <el-button :loading="auditLoading" @click="loadAudits(1)">刷新审计</el-button>
            <el-button
              :loading="auditOperation === 'export-csv'"
              :disabled="auditLoading"
              type="primary"
              plain
              @click="downloadAudits('csv')"
            >导出 CSV</el-button>
          </div>
          <el-table :data="audits" class="rg-table release-audit-table" size="small" row-key="id" aria-label="模型发布审计时间线">
            <el-table-column label="时间 / 动作" min-width="185">
              <template #default="{ row }">
                <div class="release-version-cell">
                  <strong>{{ row.createdAt || "—" }}</strong>
                  <span>{{ auditActionText(row.action) }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="状态转换" min-width="145">
              <template #default="{ row }">{{ row.fromState || "—" }} → {{ row.toState }}</template>
            </el-table-column>
            <el-table-column prop="operator" label="操作者" width="125" />
            <el-table-column prop="reason" label="原因" min-width="210" show-overflow-tooltip />
            <el-table-column label="事件哈希" min-width="150">
              <template #default="{ row }">
                <el-tag :type="row.hashValid ? 'success' : 'danger'" size="small">
                  {{ row.hashValid ? "VALID" : row.hashStatus }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="105" fixed="right">
              <template #default="{ row }">
                <el-button
                  size="small"
                  link
                  :loading="auditOperation === `verify-${row.id}`"
                  @click="verifyAudit(row.id)"
                >校验哈希</el-button>
              </template>
            </el-table-column>
            <template #empty><el-empty description="暂无发布审计记录" /></template>
          </el-table>
          <el-pagination
            v-if="auditTotal > 20"
            class="release-audit-pagination"
            background
            layout="prev, pager, next, total"
            :current-page="auditPage"
            :page-size="20"
            :total="auditTotal"
            @current-change="loadAudits"
          />
        </el-collapse-item>
      </el-collapse>
    </template>
    <el-empty v-else-if="!loading && !errorMessage" description="暂无模型发布中心数据" />
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import { ElMessageBox } from "element-plus/es/components/message-box/index.mjs";
import { canManage } from "@/stores/authState";
import type { LlmModelBudget } from "@/types";
import { useLlmModelReleaseCenter } from "@/features/rule-config/composables/useLlmModelReleaseCenter";

const {
  action,
  auditFilterAction,
  auditLoading,
  auditOperation,
  auditOperator,
  auditPage,
  auditTotal,
  audits,
  canaryTraffic,
  center,
  errorMessage,
  loading,
  load,
  loadAudits,
  promote,
  registerShadow,
  releaseKey,
  reports,
  rollback,
  selectedReport,
  selectedReportId,
  trendDays,
  runtimeMetrics,
  exportAudits,
  verifyAudit
} = useLlmModelReleaseCenter();
const advancedOpen = ref<string[]>([]);
const auditOpen = ref<string[]>([]);

const requestRollback = async (releaseId: number) => {
  try {
    const result = await ElMessageBox.prompt("回滚后新任务会立即使用目标版本，请记录可审计原因。", "确认模型回滚", {
      confirmButtonText: "确认回滚",
      cancelButtonText: "取消",
      inputPlaceholder: "例如：P95 延迟超过门禁",
      inputValidator: (value: string) => value.trim().length > 0 || "必须填写回滚原因"
    });
    await rollback(releaseId, result.value);
  } catch {
    // Cancelled dialogs intentionally leave the current server state untouched.
  }
};

const stateText = (state: string) => ({
  ACTIVE: "Active",
  CANARY: "Canary",
  SHADOW: "Shadow",
  ROLLED_BACK: "已回滚"
}[state] ?? state);

const stateType = (state: string): "success" | "warning" | "danger" | "info" => {
  if (state === "ACTIVE") return "success";
  if (state === "CANARY") return "warning";
  if (state === "ROLLED_BACK") return "danger";
  return "info";
};

const percent = (value: number) => `${(Number(value ?? 0) * 100).toFixed(1)}%`;
const rate = (numerator: number, denominator: number) => denominator > 0 ? percent(numerator / denominator) : "—";
const runtimeStateText = (state: string) => ({
  NORMAL: "正常",
  INSUFFICIENT_SAMPLE: "样本不足",
  ALERT: "告警",
  AUTO_ROLLBACK: "已自动回滚"
}[state] ?? state);
const runtimeStateType = (state: string): "success" | "warning" | "danger" | "info" => {
  if (state === "NORMAL") return "success";
  if (state === "INSUFFICIENT_SAMPLE") return "info";
  if (state === "AUTO_ROLLBACK") return "danger";
  return "warning";
};
const runtimeActionText = (action: string) => ({ NONE: "无", NOTIFY: "通知", AUTO_ROLLBACK: "自动回滚" }[action] ?? action);
const auditActionText = (action: string) => ({
  REGISTER_SHADOW: "注册 Shadow",
  PROMOTE: "发布 / 更新",
  REPLACE_ACTIVE: "替换 Active",
  ROLLBACK: "人工回滚",
  AUTO_ROLLBACK: "自动回滚"
}[action] ?? action);
const tokenText = (value: number) => value < 0 ? "未设置" : value.toLocaleString();
const budgetText = (budget: LlmModelBudget) => budget.tokenBudget > 0
  ? `${tokenText(budget.tokenUsed)} / ${tokenText(budget.tokenBudget)}`
  : budget.costBudget > 0 ? `$${Number(budget.costUsed).toFixed(2)} / $${Number(budget.costBudget).toFixed(2)}` : "未设置";

const downloadAudits = async (format: "json" | "csv") => {
  const exported = await exportAudits(format);
  if (!exported) return;
  const blob = new Blob([exported.content], { type: format === "csv" ? "text/csv;charset=utf-8" : "application/json" });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = `repoguard-release-audits.${format}`;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
};

onMounted(load);
</script>
