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
          <el-tag :type="row.eligible && !row.blockers.length ? 'success' : 'warning'">
            {{ row.eligible && !row.blockers.length ? 'PASS' : 'BLOCKED' }}
          </el-tag>
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
      :type="selectedReport.eligible && !selectedReport.blockers.length ? 'success' : 'warning'"
      :title="selectedReport.blockers.length ? selectedReport.blockers.join('；') : '报告满足当前质量门禁，可作为模型发布证据'"
      show-icon
      :closable="false"
    >
      <template #default>
        <span>报告指纹：{{ selectedReport.reportKey }} · 版本提交：{{ selectedReport.codeRevision }}</span>
      </template>
    </el-alert>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import { exportLlmEvaluationReport, fetchLlmEvaluationReports } from "@/api/config";
import type { LlmEvaluationReport } from "@/types";
import { getErrorMessage } from "@/utils/errors";

const reports = ref<LlmEvaluationReport[]>([]);
const selectedReport = ref<LlmEvaluationReport | null>(null);
const loading = ref(false);
const errorMessage = ref("");

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

const percent = (value: number) => `${(Number(value ?? 0) * 100).toFixed(1)}%`;

onMounted(loadReports);
</script>
