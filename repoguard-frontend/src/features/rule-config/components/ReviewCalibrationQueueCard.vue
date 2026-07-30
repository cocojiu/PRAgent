<template>
  <section class="dashboard-card calibration-card">
    <div class="policy-governance-heading">
      <div>
        <h2>OBSERVE 人工校准队列</h2>
        <p>只统计当前检测器、规则配置和策略版本的完整高危结果；明确标注满 30 个样本后才进入质量评级。</p>
      </div>
      <div class="calibration-actions">
        <el-select
          v-model="calibrationRuleId"
          aria-label="校准规则"
          placeholder="选择高危规则"
          @change="loadCalibrationQueue(true)"
        >
          <el-option
            v-for="rule in calibrationRuleOptions"
            :key="rule.id"
            :label="`${rule.id} · ${rule.name}`"
            :value="rule.id"
          />
        </el-select>
        <el-button :loading="calibrationLoading" @click="loadCalibrationQueue(true)">刷新队列</el-button>
      </div>
    </div>

    <el-alert
      v-if="calibrationError"
      class="page-alert"
      type="error"
      :title="calibrationError"
      show-icon
      :closable="false"
    />

    <template v-if="calibrationQueue">
      <div class="calibration-version-banner">
        <div>
          <span>固定质量版本</span>
          <strong>{{ calibrationQueue.version.ruleId }} · {{ calibrationQueue.version.detectorVersion }}</strong>
          <small>
            C{{ calibrationQueue.version.ruleConfigVersion }}
            · Prompt {{ calibrationQueue.version.promptVersion }}
            · {{ calibrationQueue.version.aggregationVersion }}
          </small>
        </div>
        <el-tooltip :content="calibrationQueue.version.versionKey">
          <el-tag type="info">策略快照 #{{ calibrationQueue.version.strategySnapshotId }}</el-tag>
        </el-tooltip>
        <el-tag :type="calibrationQueue.version.replayVerified ? 'success' : 'warning'">
          {{ calibrationQueue.version.replayVerified ? "回放已验证" : "回放未验证" }}
        </el-tag>
        <el-tag
          :type="calibrationQueue.version.ruleEnforcementMode === 'observe'
            && calibrationQueue.version.strategyEnforcementMode === 'observe' ? 'info' : 'warning'"
        >
          规则 {{ enforcementModeText(calibrationQueue.version.ruleEnforcementMode) }}
          / 策略 {{ enforcementModeText(calibrationQueue.version.strategyEnforcementMode) }}
        </el-tag>
      </div>

      <div class="calibration-progress">
        <div>
          <strong>
            {{ calibrationQueue.labeledHighRiskSamples }} / {{ calibrationQueue.targetLabeledSamples }}
          </strong>
          <span>明确标注进度</span>
        </div>
        <el-progress
          :percentage="calibrationProgressPercentage"
          :status="calibrationProgressPercentage >= 100 ? 'success' : undefined"
        />
      </div>

      <div class="calibration-metric-grid">
        <div><span>当前版本高危</span><strong>{{ calibrationQueue.totalHighRiskFindings }}</strong></div>
        <div><span>确认有效</span><strong>{{ calibrationQueue.confirmedValidSamples }}</strong></div>
        <div><span>确认误报</span><strong>{{ calibrationQueue.falsePositiveSamples }}</strong></div>
        <div><span>待明确标注</span><strong>{{ calibrationQueue.pendingHighRiskSamples }}</strong></div>
        <div><span>距门槛还差</span><strong>{{ calibrationQueue.remainingToTarget }}</strong></div>
        <div>
          <span>质量门禁</span>
          <el-tag :type="qualityStatusType(calibrationQueue.qualityGate.status)">
            {{ calibrationQueue.qualityGate.status }}
          </el-tag>
        </div>
      </div>

      <el-alert
        v-if="calibrationQueue.qualityGate.blockers.length"
        class="calibration-gate-alert"
        type="warning"
        :title="calibrationQueue.qualityGate.blockers.join('；')"
        show-icon
        :closable="false"
      />

      <el-table
        v-loading="calibrationLoading"
        :data="calibrationQueue.samples"
        class="rg-table calibration-table"
        size="small"
        row-key="findingId"
        aria-label="OBSERVE 人工校准样本"
      >
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="calibration-evidence">
              <div><span>证据</span><p>{{ row.evidence || "未记录" }}</p></div>
              <div><span>影响</span><p>{{ row.impact || "未记录" }}</p></div>
              <div><span>前置条件</span><p>{{ row.preconditions || "未记录" }}</p></div>
              <div><span>修复建议</span><p>{{ row.recommendation || "未记录" }}</p></div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="PR / 仓库" min-width="190">
          <template #default="{ row }">
            <div class="calibration-pr-cell">
              <strong>PR #{{ row.prNumber ?? "-" }}</strong>
              <span>{{ row.organization }} / {{ row.repository }}</span>
              <small>{{ row.taskCreatedAt }}</small>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="Finding" min-width="300">
          <template #default="{ row }">
            <div class="calibration-finding-cell">
              <div>
                <span :class="`risk-pill ${String(row.severity).toLowerCase()}`">
                  {{ calibrationRiskText(row) }}
                </span>
                <el-tag size="small" type="info">{{ row.source }}</el-tag>
                <el-tag size="small">{{ row.verificationStatus }}</el-tag>
              </div>
              <strong>{{ row.message }}</strong>
              <span>{{ findingLocation(row) }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="feedbackStatus" label="反馈状态" width="120" />
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" plain @click="openCalibrationSample(row)">
              查看并标注
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="当前固定版本没有待标注高危样本，请继续运行 OBSERVE 任务采集数据" />
        </template>
      </el-table>
    </template>
    <el-empty
      v-else-if="!calibrationLoading && !calibrationError"
      description="请选择高危规则加载固定版本校准队列"
    />
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import { useRoute, useRouter } from "vue-router";
import { fetchReviewCalibrationQueue } from "@/api/config";
import { routeNames } from "@/router/names";
import type {
  ReviewCalibrationQueue,
  ReviewCalibrationSample,
  ReviewRuleConfig
} from "@/types";
import { getErrorMessage } from "@/utils/errors";
import { riskText } from "@/utils/risk";

const props = defineProps<{
  rules: ReviewRuleConfig[];
}>();

const route = useRoute();
const router = useRouter();
const calibrationRuleId = ref("");
const calibrationQueue = ref<ReviewCalibrationQueue | null>(null);
const calibrationLoading = ref(false);
const calibrationError = ref("");
let calibrationRequestEpoch = 0;

const calibrationRuleOptions = computed(() =>
  props.rules.filter((rule) => rule.severity === "high" || rule.severity === "critical")
);

const calibrationProgressPercentage = computed(() => {
  if (!calibrationQueue.value || calibrationQueue.value.targetLabeledSamples <= 0) {
    return 0;
  }
  return Math.min(
    100,
    Math.round(
      calibrationQueue.value.labeledHighRiskSamples
        * 100
        / calibrationQueue.value.targetLabeledSamples
    )
  );
});

const loadCalibrationQueue = async (showErrorToast = true) => {
  const requestEpoch = ++calibrationRequestEpoch;
  if (!calibrationRuleId.value) {
    calibrationQueue.value = null;
    calibrationError.value = "";
    return;
  }
  calibrationLoading.value = true;
  calibrationError.value = "";
  calibrationQueue.value = null;
  const requestedRuleId = calibrationRuleId.value;
  try {
    const response = await fetchReviewCalibrationQueue(requestedRuleId, {
      limit: 30,
      includeIgnored: false
    });
    if (requestEpoch === calibrationRequestEpoch && requestedRuleId === calibrationRuleId.value) {
      calibrationQueue.value = response;
    }
  } catch (error) {
    if (requestEpoch !== calibrationRequestEpoch) {
      return;
    }
    calibrationError.value = getErrorMessage(error, "校准队列加载失败");
    if (showErrorToast && requestedRuleId === calibrationRuleId.value) {
      ElMessage.error(calibrationError.value);
    }
  } finally {
    if (requestEpoch === calibrationRequestEpoch) {
      calibrationLoading.value = false;
    }
  }
};

watch(
  calibrationRuleOptions,
  (options) => {
    const requestedRuleId = typeof route.query.calibrationRule === "string"
      ? route.query.calibrationRule.toUpperCase()
      : "";
    const preferredRuleId = options.some((rule) => rule.id === requestedRuleId)
      ? requestedRuleId
      : options.some((rule) => rule.id === "RG-AUTH-001")
        ? "RG-AUTH-001"
        : options[0]?.id ?? "";
    if (!calibrationRuleId.value || !options.some((rule) => rule.id === calibrationRuleId.value)) {
      calibrationRuleId.value = preferredRuleId;
    }
    void loadCalibrationQueue(false);
  },
  { immediate: true }
);

const enforcementModeText = (mode: ReviewRuleConfig["enforcementMode"]) => {
  if (mode === "block") return "阻断";
  if (mode === "comment") return "评论";
  return "观察";
};

const findingLocation = (sample: ReviewCalibrationSample) =>
  sample.lineNumber
    ? `${sample.filePath}:${sample.lineNumber}`
    : sample.filePath || "跨文件证据";

const calibrationRiskText = (sample: ReviewCalibrationSample) =>
  riskText(sample.severity.toLowerCase() as ReviewRuleConfig["severity"]);

const qualityStatusType = (status: string): "success" | "warning" | "danger" | "info" => {
  if (status === "PASS") return "success";
  if (status === "ALERT") return "danger";
  if (status === "INSUFFICIENT_SAMPLE") return "warning";
  return "info";
};

const openCalibrationSample = (sample: ReviewCalibrationSample) => {
  void router.push({
    name: routeNames.taskDetail,
    params: { id: sample.taskId },
    query: {
      from: "calibration",
      calibrationRule: calibrationRuleId.value,
      findingId: String(sample.findingId)
    }
  });
};
</script>
