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
                  :label="`#${report.id} · ${report.provider}/${report.model} · ${report.datasetId}@${report.datasetVersion}`"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="Canary 流量">
              <el-input-number v-model="canaryTraffic" :min="1" :max="100" :disabled="!canManage" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :disabled="!canManage" :loading="action === 'shadow'" @click="registerShadow">
                注册 Shadow
              </el-button>
              <el-button type="success" :disabled="!canManage || !selectedReport" :loading="action.startsWith('promote-')" @click="promote()">
                发布 Canary
              </el-button>
            </el-form-item>
          </el-form>
          <el-alert
            v-if="selectedReport"
            class="release-report-evidence"
            :type="selectedReport.eligible && !selectedReport.blockers.length ? 'success' : 'warning'"
            :title="selectedReport.blockers.length ? `报告阻断：${selectedReport.blockers.join('；')}` : '指标由服务端报告提供，客户端不可修改'"
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
  canaryTraffic,
  center,
  errorMessage,
  loading,
  load,
  promote,
  registerShadow,
  releaseKey,
  reports,
  rollback,
  selectedReport,
  selectedReportId,
  trendDays
} = useLlmModelReleaseCenter();
const advancedOpen = ref<string[]>([]);

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
const tokenText = (value: number) => value < 0 ? "未设置" : value.toLocaleString();
const budgetText = (budget: LlmModelBudget) => budget.tokenBudget > 0
  ? `${tokenText(budget.tokenUsed)} / ${tokenText(budget.tokenBudget)}`
  : budget.costBudget > 0 ? `$${Number(budget.costUsed).toFixed(2)} / $${Number(budget.costBudget).toFixed(2)}` : "未设置";

onMounted(load);
</script>
