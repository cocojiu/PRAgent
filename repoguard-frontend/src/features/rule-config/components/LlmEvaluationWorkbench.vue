<template>
  <section class="dashboard-card evaluation-workbench-card">
    <div class="policy-governance-heading">
      <div>
        <h2>真实 PR 质量评估工作台</h2>
        <p>仅展示服务端生成的不可变聚合报告；原始 PR、补丁、Prompt 和供应商响应不会写入系统。</p>
      </div>
      <div class="evaluation-actions">
        <el-button :loading="loading" @click="loadReports">刷新报告</el-button>
        <el-button v-if="selectedReport" @click="downloadReport('json')">导出 JSON</el-button>
        <el-button v-if="selectedReport" @click="downloadReport('html')">导出 HTML</el-button>
        <el-button
          v-if="selectedReport && selectedReport.lifecycleStatus !== 'FROZEN' && selectedReport.lifecycleStatus !== 'DELETED'"
          type="warning"
          plain
          @click="transitionLifecycle('FREEZE')"
        >冻结报告</el-button>
        <el-button
          v-if="selectedReport && selectedReport.lifecycleStatus === 'ACTIVE'"
          type="danger"
          plain
          @click="transitionLifecycle('REVOKE_AUTHORIZATION')"
        >撤销授权</el-button>
        <el-button
          v-if="selectedReport && selectedReport.lifecycleStatus !== 'DELETED'"
          type="danger"
          plain
          @click="transitionLifecycle('DELETE')"
        >软删除</el-button>
      </div>
    </div>

    <div class="evaluation-run-panel" aria-label="启动真实 PR 评估">
      <div class="evaluation-run-heading">
        <strong>运行外部真实 PR 评估</strong>
        <span>数据目录必须由平台管理员预置在受控根目录下，系统不会上传或持久化源码。</span>
      </div>
      <div class="evaluation-run-form">
        <el-input v-model="runForm.runKey" placeholder="运行幂等键" aria-label="运行幂等键" />
        <el-input v-model="runForm.dataDirectory" placeholder="受控数据目录相对路径" aria-label="受控数据目录" />
        <el-input-number v-model="runForm.maxConcurrency" :min="1" :max="8" controls-position="right" aria-label="最大并发" />
        <el-input-number v-model="runForm.maxTokens" :min="1" :max="1000000" controls-position="right" aria-label="最大令牌数" />
        <el-input-number v-model="runForm.maxCost" :min="0" :precision="4" :step="1" controls-position="right" aria-label="最大费用" />
        <el-input-number v-model="runForm.maxDurationSeconds" :min="1" :max="3600" controls-position="right" aria-label="最大时长秒数" />
        <el-button type="primary" :loading="runLoading" @click="startRun">启动评估</el-button>
      </div>
      <el-alert
        v-if="activeRun"
        class="evaluation-run-status"
        :type="runAlertType(activeRun.status)"
        :title="`运行 ${activeRun.runKey}：${activeRun.status}`"
        :closable="false"
      >
        <template #default>
          <span>
            {{ activeRun.completedSamples }} / {{ activeRun.totalSamples || "待加载" }} 个样本，
            {{ activeRun.totalTokens }} tokens，费用 {{ Number(activeRun.totalCost ?? 0).toFixed(4) }}
            <span v-if="activeRun.failureCode"> · {{ activeRun.failureCode }}</span>
            <span v-if="activeRun.reportId"> · 报告 #{{ activeRun.reportId }}</span>
          </span>
          <el-button
            v-if="activeRun.status === 'QUEUED' || activeRun.status === 'RUNNING'"
            size="small"
            type="danger"
            plain
            @click="cancelRun"
          >取消运行</el-button>
        </template>
      </el-alert>
    </div>

    <el-alert
      v-if="errorMessage"
      class="page-alert"
      type="error"
      :title="errorMessage"
      show-icon
      :closable="false"
    />

    <el-table
      v-loading="loading"
      :data="reports"
      class="rg-table evaluation-report-table"
      size="small"
      row-key="id"
      highlight-current-row
      aria-label="LLM 评估报告列表"
      @current-change="selectReport"
    >
      <el-table-column label="报告" min-width="190">
        <template #default="{ row }">
          <div class="evaluation-report-cell">
            <strong>#{{ row.id }} · {{ row.model }}</strong>
            <span>{{ row.provider }} · {{ row.datasetId }}@{{ row.datasetVersion }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="样本 / 分片" width="130">
        <template #default="{ row }">{{ row.sampleCount }} / {{ row.fixedRegressionSamples }}+{{ row.rollingObservationSamples }}</template>
      </el-table-column>
      <el-table-column label="Precision / Recall" width="160">
        <template #default="{ row }">{{ percent(row.precision) }} / {{ percent(row.recall) }}</template>
      </el-table-column>
      <el-table-column label="锚点 / 重复 / 解析失败" width="205">
        <template #default="{ row }">{{ percent(row.anchorRate) }} / {{ percent(row.duplicateRate) }} / {{ percent(row.parseFailureRate) }}</template>
      </el-table-column>
      <el-table-column label="门禁" width="110">
        <template #default="{ row }">
          <el-tag :type="reportGateType(row)">
            {{ reportGateLabel(row) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="生命周期" width="160">
        <template #default="{ row }">
          <el-tag :type="lifecycleTag(row.lifecycleStatus)">{{ row.lifecycleStatus }}</el-tag>
          <small v-if="row.expiresAt" class="evaluation-expiry">到期 {{ formatDate(row.expiresAt) }}</small>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="130" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="primary" plain @click.stop="selectReport(row)">查看报告</el-button>
        </template>
      </el-table-column>
      <template #empty><el-empty description="暂无服务端评估报告" /></template>
    </el-table>

    <el-alert
      v-if="selectedReport"
      class="evaluation-report-detail"
      :type="selectedReport.status === 'PROVISIONAL' ? 'info' : selectedReport.eligible && !selectedReport.blockers.length ? 'success' : 'warning'"
      :title="reportDetailTitle(selectedReport)"
      show-icon
      :closable="false"
    >
      <template #default>
        <span>
          报告指纹：{{ selectedReport.reportKey }} · 版本提交：{{ selectedReport.codeRevision }} ·
          保留 {{ selectedReport.retentionDays }} 天
        </span>
      </template>
    </el-alert>
  </section>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, ref } from "vue";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import {
  cancelLlmEvaluationRun,
  exportLlmEvaluationReport,
  fetchLlmEvaluationRun,
  fetchLlmEvaluationReports,
  startLlmEvaluationRun,
  transitionLlmEvaluationReportLifecycle
} from "@/api/config";
import type { LlmEvaluationReport, LlmEvaluationRun, LlmEvaluationRunRequest } from "@/types";
import { getErrorMessage } from "@/utils/errors";

const reports = ref<LlmEvaluationReport[]>([]);
const selectedReport = ref<LlmEvaluationReport | null>(null);
const loading = ref(false);
const errorMessage = ref("");
const runLoading = ref(false);
const activeRun = ref<LlmEvaluationRun | null>(null);
const runForm = ref<LlmEvaluationRunRequest>({
  runKey: "",
  dataDirectory: "",
  maxConcurrency: 2,
  maxTokens: 100000,
  maxCost: 100,
  maxDurationSeconds: 1800
});
let pollTimer: number | undefined;

const loadReports = async () => {
  loading.value = true;
  errorMessage.value = "";
  try {
    reports.value = await fetchLlmEvaluationReports(50);
    selectedReport.value = reports.value[0] ?? null;
  } catch (error) {
    errorMessage.value = getErrorMessage(error, "评估报告加载失败");
  } finally {
    loading.value = false;
  }
};

const selectReport = (report: LlmEvaluationReport | null) => {
  selectedReport.value = report;
};

const startRun = async () => {
  runLoading.value = true;
  errorMessage.value = "";
  try {
    activeRun.value = await startLlmEvaluationRun(runForm.value);
    beginPolling();
  } catch (error) {
    errorMessage.value = getErrorMessage(error, "评估运行启动失败");
  } finally {
    runLoading.value = false;
  }
};

const refreshRun = async () => {
  if (!activeRun.value) return;
  try {
    activeRun.value = await fetchLlmEvaluationRun(activeRun.value.runId);
    if (!["QUEUED", "RUNNING"].includes(activeRun.value.status)) {
      stopPolling();
      if (activeRun.value.status === "COMPLETE") {
        ElMessage.success("评估运行完成，报告已生成");
        await loadReports();
      }
    }
  } catch (error) {
    stopPolling();
    errorMessage.value = getErrorMessage(error, "评估运行状态加载失败");
  }
};

const beginPolling = () => {
  stopPolling();
  void refreshRun();
  pollTimer = window.setInterval(() => void refreshRun(), 2000);
};

const stopPolling = () => {
  if (pollTimer !== undefined) {
    window.clearInterval(pollTimer);
    pollTimer = undefined;
  }
};

const cancelRun = async () => {
  if (!activeRun.value) return;
  try {
    activeRun.value = await cancelLlmEvaluationRun(activeRun.value.runId);
    stopPolling();
    ElMessage.success("评估运行已取消");
  } catch (error) {
    ElMessage.error(getErrorMessage(error, "评估运行取消失败"));
  }
};

const runAlertType = (status: string) => {
  if (status === "COMPLETE") return "success";
  if (status === "FAILED" || status === "CANCELLED") return "warning";
  return "info";
};

const reportGateLabel = (report: LlmEvaluationReport) => {
  if (report.status === "PROVISIONAL") return "PROVISIONAL";
  return report.eligible && !report.blockers.length ? "PASS" : "BLOCKED";
};

const reportGateType = (report: LlmEvaluationReport) => {
  if (report.status === "PROVISIONAL") return "info";
  return report.eligible && !report.blockers.length ? "success" : "warning";
};

const reportDetailTitle = (report: LlmEvaluationReport) => {
  if (report.status === "PROVISIONAL") {
    return "小样本验收报告仅供观察、比较和导出，不能作为模型发布证据";
  }
  return report.blockers.length
    ? report.blockers.join("；")
    : "报告满足当前质量门禁，可作为模型发布证据";
};

const downloadReport = async (format: "json" | "html") => {
  if (!selectedReport.value) return;
  try {
    const exported = await exportLlmEvaluationReport(selectedReport.value.id, format);
    const url = URL.createObjectURL(new Blob([exported.content], { type: format === "html" ? "text/html" : "application/json" }));
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = `repoguard-evaluation-${exported.reportId}.${format}`;
    anchor.click();
    URL.revokeObjectURL(url);
  } catch (error) {
    ElMessage.error(getErrorMessage(error, "评估报告导出失败"));
  }
};

const transitionLifecycle = async (action: "FREEZE" | "REVOKE_AUTHORIZATION" | "DELETE") => {
  if (!selectedReport.value) return;
  const reason = window.prompt("请输入生命周期操作原因（最多 512 字）", "评估工作台治理操作")?.trim();
  if (!reason) return;
  const secondApprover = window.prompt("如需双人审批，请输入第二位管理员账号（管理员可留空）")?.trim();
  try {
    await transitionLlmEvaluationReportLifecycle(selectedReport.value.id, {
      action,
      reason,
      secondApprover: secondApprover || undefined,
      idempotencyKey: `${action.toLowerCase()}-${selectedReport.value.id}-${Date.now()}`
    });
    ElMessage.success("评估报告生命周期已更新");
    await loadReports();
  } catch (error) {
    ElMessage.error(getErrorMessage(error, "评估报告生命周期操作失败"));
  }
};

const percent = (value: number) => `${(Number(value ?? 0) * 100).toFixed(1)}%`;
const formatDate = (value?: string) => value ? new Date(value).toLocaleString() : "";
const lifecycleTag = (status: string) => {
  if (status === "ACTIVE") return "success";
  if (status === "DELETED" || status === "AUTHORIZATION_REVOKED") return "danger";
  return "warning";
};

onMounted(loadReports);
onUnmounted(stopPolling);
</script>
