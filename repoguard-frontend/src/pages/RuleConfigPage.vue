<template>
  <div v-loading="loading" class="rules-page">
    <div class="page-heading page-heading-row">
      <div>
        <h1>规则配置</h1>
        <p>管理代码审查规则、严重级别和启用状态</p>
      </div>
      <div class="page-heading-actions">
        <el-tag type="info" size="large">内置规则 + 安全声明式规则</el-tag>
        <el-button type="primary" :disabled="!canManage" @click="openCreateDialog">新增声明式规则</el-button>
      </div>
    </div>

    <el-alert v-if="errorMessage" class="page-alert" type="error" :title="errorMessage" show-icon :closable="false" />

    <MetricGrid :metrics="ruleMetricItems" :resolve-icon="getMetricIcon" />

    <section v-if="strategyPolicy" class="dashboard-card policy-governance-card">
      <div class="policy-governance-heading">
        <div>
          <h2>审查策略发布治理</h2>
          <p>新策略先观察、经回放和人工样本校准后再逐级提升处置模式。</p>
        </div>
        <div class="policy-governance-actions">
          <el-select v-model="strategyTargetMode" :disabled="!canManage" aria-label="策略处置模式">
            <el-option label="仅观察" value="observe" />
            <el-option label="评论" value="comment" />
            <el-option label="阻断" value="block" />
          </el-select>
          <el-button
            type="primary"
            :disabled="!canManage || strategyTargetMode === strategyPolicy.enforcementMode"
            :loading="strategySaving"
            @click="saveStrategyEnforcement"
          >应用模式</el-button>
          <el-button @click="openStrategyVersions">版本历史</el-button>
        </div>
      </div>
      <div class="policy-version-grid">
        <div><span>策略快照</span><strong>#{{ strategyPolicy.snapshotId }}</strong></div>
        <div><span>Prompt</span><strong>{{ strategyPolicy.promptVersion }}</strong></div>
        <div><span>上下文</span><strong>{{ strategyPolicy.contextVersion }}</strong></div>
        <div><span>聚合器</span><strong>{{ strategyPolicy.aggregationVersion }}</strong></div>
        <div><span>回放验证</span><strong>{{ strategyPolicy.replayVerified ? "已通过" : "未通过" }}</strong></div>
        <div>
          <span>当前模式</span>
          <el-tag :type="strategyPolicy.enforcementMode === 'block' ? 'danger' : strategyPolicy.enforcementMode === 'comment' ? 'warning' : 'info'">
            {{ enforcementModeText(strategyPolicy.enforcementMode) }}
          </el-tag>
        </div>
        <div>
          <span>质量门禁</span>
          <el-tag :type="qualityStatusType(strategyPolicy.qualityGate.status)">{{ strategyPolicy.qualityGate.status }}</el-tag>
        </div>
      </div>
      <el-alert
        v-if="strategyPolicy.qualityGate.blockers.length"
        type="warning"
        :title="strategyPolicy.qualityGate.blockers.join('；')"
        show-icon
        :closable="false"
      />
    </section>

    <RepositoryPolicyPanel />
    <ReviewCalibrationQueueCard :rules="rules" />
    <LlmEvaluationWorkbench />
    <LlmModelReleaseCenter />

    <section class="rule-layout">
      <article class="rule-panel">
        <div class="filter-bar rule-filter">
          <el-select v-model="severityFilter" placeholder="全部严重级别" clearable>
            <el-option label="全部严重级别" value="" />
            <el-option label="高风险" value="high" />
            <el-option label="中风险" value="medium" />
            <el-option label="低风险" value="low" />
            <el-option label="提示" value="info" />
          </el-select>
          <el-select v-model="statusFilter" placeholder="全部状态" clearable>
            <el-option label="全部状态" value="" />
            <el-option label="已启用" value="enabled" />
            <el-option label="已停用" value="disabled" />
          </el-select>
          <el-input v-model="keyword" class="search-input" placeholder="搜索规则名称或规则 ID" clearable>
            <template #suffix><Search :size="18" /></template>
          </el-input>
        </div>

        <el-table :data="filteredRules" class="rg-table task-table" size="large" aria-label="规则配置列表">
          <el-table-column prop="id" label="规则 ID" min-width="140" />
          <el-table-column prop="name" label="规则名称" min-width="190" />
          <el-table-column prop="scope" label="适用范围" min-width="190" />
          <el-table-column prop="applicableLanguages" label="适用语言" min-width="150" />
          <el-table-column prop="filePatterns" label="文件匹配" min-width="190" show-overflow-tooltip />
          <el-table-column label="严重级别" width="120">
            <template #default="{ row }">
              <span :class="`risk-pill ${row.severity}`">{{ riskText(row.severity) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="启用状态" width="120">
            <template #default="{ row }">
              <el-switch
                v-model="row.status"
                active-value="enabled"
                inactive-value="disabled"
                :disabled="!canManage"
                :loading="statusSavingId === row.id"
                @change="toggleRule(row, $event)"
              />
            </template>
          </el-table-column>
          <el-table-column prop="hitCount" label="命中次数" width="110" />
          <el-table-column prop="confidence" label="置信度" width="100" />
          <el-table-column label="处置模式" width="110">
            <template #default="{ row }">
              <el-tag :type="row.enforcementMode === 'block' ? 'danger' : row.enforcementMode === 'comment' ? 'warning' : 'info'">
                {{ enforcementModeText(row.enforcementMode) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="版本 / 门禁" min-width="180">
            <template #default="{ row }">
              <div class="rule-version-cell">
                <span>{{ row.detectorVersion }} · C{{ row.configVersion }} / P{{ row.policyVersion }}</span>
                <el-tag size="small" :type="qualityStatusType(row.qualityGate.status)">{{ row.qualityGate.status }}</el-tag>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="最近更新" min-width="180">
            <template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="180" fixed="right">
            <template #default="{ row }">
              <el-button size="small" type="primary" plain :disabled="!canManage" @click="openEditDialog(row)">编辑</el-button>
              <el-button size="small" @click="openRuleVersions(row)">历史</el-button>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty description="暂无规则配置" />
          </template>
        </el-table>
      </article>

      <aside class="dashboard-card rule-doc-card">
        <h2>规则说明</h2>
        <div v-for="rule in topRuleDocs" :key="rule.id" class="rule-doc-item">
          <strong>{{ rule.id }} · {{ rule.name }}</strong>
          <p>{{ rule.description }}</p>
          <p v-if="rule.falsePositiveGuidance">误报说明：{{ rule.falsePositiveGuidance }}</p>
        </div>
        <el-empty v-if="!topRuleDocs.length" description="暂无规则说明" />
      </aside>
    </section>

    <section class="dashboard-card quality-groups-card">
      <div class="policy-governance-heading">
        <div>
          <h2>质量反馈分组</h2>
          <p>按规则、来源、仓库、语言和版本追踪显式标注；少于 30 个样本时只展示数据，不自动评级。</p>
        </div>
      </div>
      <el-table :data="qualityGroups" class="rg-table" size="small" aria-label="审查质量反馈分组">
        <el-table-column prop="ruleId" label="规则" min-width="130" />
        <el-table-column prop="source" label="来源" min-width="100" />
        <el-table-column prop="repository" label="仓库" min-width="150" show-overflow-tooltip />
        <el-table-column prop="language" label="语言" min-width="90" />
        <el-table-column prop="versionKey" label="版本" min-width="220" show-overflow-tooltip />
        <el-table-column label="标注 / 覆盖率" min-width="130">
          <template #default="{ row }">{{ row.labeledCount }} / {{ percent(row.labeledCoverage) }}</template>
        </el-table-column>
        <el-table-column label="准确率 / 误报率" min-width="150">
          <template #default="{ row }">{{ percent(row.labeledPrecision) }} / {{ percent(row.labeledFalsePositiveRate) }}</template>
        </el-table-column>
        <el-table-column label="高危 / 阻断 / 撤销" min-width="170">
          <template #default="{ row }">{{ row.highRiskCount }} / {{ row.blockingCount }} / {{ row.revokedBlockingCount }}</template>
        </el-table-column>
        <el-table-column label="锚点 / 重复" min-width="140">
          <template #default="{ row }">{{ percent(row.anchorRate) }} / {{ percent(row.duplicateRate) }}</template>
        </el-table-column>
        <el-table-column label="阈值状态" min-width="150" fixed="right">
          <template #default="{ row }">
            <el-tooltip :content="row.thresholdAlerts.join('；') || '未触发阈值告警'">
              <el-tag :type="qualityStatusType(row.thresholdStatus)">{{ row.thresholdStatus }}</el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
        <template #empty><el-empty description="暂无显式反馈样本" /></template>
      </el-table>
    </section>

    <el-dialog v-model="dialogVisible" :title="editingRuleId ? '编辑规则' : '新增声明式规则'" width="560px">
      <el-form label-position="top" class="rule-form">
        <el-form-item label="规则 ID">
          <el-input v-model="ruleForm.id" :disabled="Boolean(editingRuleId)" placeholder="例如 RG-JAVA-004" />
        </el-form-item>
        <el-form-item label="规则名称">
          <el-input v-model="ruleForm.name" placeholder="请输入规则名称" />
        </el-form-item>
        <el-form-item label="适用范围">
          <el-input v-model="ruleForm.scope" placeholder="例如 Java Patch" />
        </el-form-item>
        <el-form-item label="适用语言">
          <el-input v-model="ruleForm.applicableLanguages" placeholder="例如 Java,YAML,Properties" />
        </el-form-item>
        <el-form-item label="文件匹配">
          <el-input v-model="ruleForm.filePatterns" placeholder="例如 *.java,application*.yml" />
        </el-form-item>
        <el-form-item label="检测器类型">
          <el-select v-model="ruleForm.detectorType" :disabled="Boolean(editingRuleId && ruleForm.detectorType === 'BUILTIN')">
            <el-option label="内置检测器" value="BUILTIN" />
            <el-option label="正则表达式" value="REGEX" />
            <el-option label="受限 AST 查询" value="AST" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="ruleForm.detectorType !== 'BUILTIN'" label="匹配表达式">
          <el-input v-model="ruleForm.matcherExpression" placeholder="正则，或 token:execute / call:save" />
        </el-form-item>
        <el-form-item v-if="ruleForm.detectorType !== 'BUILTIN'" label="例外文件匹配">
          <el-input v-model="ruleForm.exceptionPatterns" placeholder="例如 **/generated/**,**/*.snap" />
        </el-form-item>
        <div class="rule-form-grid">
          <el-form-item label="严重级别">
            <el-select v-model="ruleForm.severity">
              <el-option label="高风险" value="high" />
              <el-option label="中风险" value="medium" />
              <el-option label="低风险" value="low" />
              <el-option label="提示" value="info" />
            </el-select>
          </el-form-item>
          <el-form-item label="启用状态">
            <el-select v-model="ruleForm.status">
              <el-option label="已启用" value="enabled" />
              <el-option label="已停用" value="disabled" />
            </el-select>
          </el-form-item>
        </div>
        <el-form-item label="置信度">
          <el-input-number v-model="ruleForm.confidence" :min="0" :max="100" />
        </el-form-item>
        <el-form-item label="处置模式">
          <el-select v-model="ruleForm.enforcementMode">
            <el-option label="仅观察（不评论、不阻断）" value="observe" />
            <el-option label="评论（不阻断）" value="comment" />
            <el-option label="阻断候选" value="block" />
          </el-select>
        </el-form-item>
        <el-form-item label="规则说明">
          <el-input v-model="ruleForm.description" type="textarea" :rows="4" placeholder="描述规则命中的场景和建议" />
        </el-form-item>
        <el-form-item label="命中示例">
          <el-input v-model="ruleForm.positiveExample" type="textarea" :rows="3" placeholder="记录一个典型命中示例" />
        </el-form-item>
        <el-form-item label="误报说明">
          <el-input v-model="ruleForm.falsePositiveGuidance" type="textarea" :rows="3" placeholder="说明哪些场景可以标记为误报" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!canManage" :loading="saving" @click="saveRule">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="ruleVersionDialogVisible"
      :title="`${selectedRuleId} 策略版本历史`"
      width="920px"
      @closed="cancelRuleHistoryRequest"
    >
      <el-table v-loading="ruleHistoryLoading" :data="ruleVersions" class="rg-table" size="small">
        <el-table-column prop="policyVersion" label="策略版本" width="100" />
        <el-table-column prop="configVersion" label="配置版本" width="100" />
        <el-table-column prop="detectorVersion" label="检测器" min-width="140" />
        <el-table-column prop="enforcementMode" label="模式" width="90" />
        <el-table-column prop="changeType" label="变更类型" width="110" />
        <el-table-column label="创建时间" min-width="190">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-tag v-if="row.active" type="success">当前</el-tag>
            <el-button
              v-else
              size="small"
              type="primary"
              plain
              :disabled="!canManage"
              :loading="rollbackSavingId === `rule-${row.policyVersion}`"
              @click="rollbackRuleVersion(row.policyVersion)"
            >回滚</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="ruleHistoryHasMore" class="history-load-more">
        <el-button :loading="ruleHistoryLoading" @click="loadMoreRuleVersions">加载更多</el-button>
      </div>
    </el-dialog>

    <el-dialog
      v-model="strategyVersionDialogVisible"
      title="审查策略版本历史"
      width="980px"
      @closed="cancelStrategyHistoryRequest"
    >
      <el-table v-loading="strategyHistoryLoading" :data="strategyVersions" class="rg-table" size="small">
        <el-table-column prop="snapshotId" label="快照" width="80" />
        <el-table-column prop="strategyVersion" label="策略版本" width="100" />
        <el-table-column prop="promptVersion" label="Prompt" width="110" />
        <el-table-column prop="aggregationVersion" label="聚合器" min-width="140" />
        <el-table-column prop="enforcementMode" label="模式" width="90" />
        <el-table-column prop="changeType" label="变更类型" width="110" />
        <el-table-column label="创建时间" min-width="190">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-tag v-if="row.active" type="success">当前</el-tag>
            <el-button
              v-else
              size="small"
              type="primary"
              plain
              :disabled="!canManage"
              :loading="rollbackSavingId === `strategy-${row.snapshotId}`"
              @click="rollbackStrategyVersion(row.snapshotId)"
            >回滚</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="strategyHistoryHasMore" class="history-load-more">
        <el-button :loading="strategyHistoryLoading" @click="loadMoreStrategyVersions">加载更多</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import "@/features/rule-config/ruleConfig.css";
import { computed, onMounted } from "vue";
import { CheckCircle, ListChecks, Search, ShieldAlert, Target, Zap } from "@lucide/vue";
import { canManage } from "@/stores/authState";
import MetricGrid, { type MetricGridItem } from "@/components/MetricGrid.vue";
import { useMetricIcon } from "@/composables/useMetricIcon";
import ReviewCalibrationQueueCard from "@/features/rule-config/components/ReviewCalibrationQueueCard.vue";
import LlmEvaluationWorkbench from "@/features/rule-config/components/LlmEvaluationWorkbench.vue";
import LlmModelReleaseCenter from "@/features/rule-config/components/LlmModelReleaseCenter.vue";
import RepositoryPolicyPanel from "@/features/rule-config/components/RepositoryPolicyPanel.vue";
import { useReviewPolicyHistory } from "@/features/rule-config/composables/useReviewPolicyHistory";
import { useReviewRuleCatalog } from "@/features/rule-config/composables/useReviewRuleCatalog";
import { useReviewRuleEditor } from "@/features/rule-config/composables/useReviewRuleEditor";
import { useReviewStrategyGovernance } from "@/features/rule-config/composables/useReviewStrategyGovernance";
import type { ReviewRuleConfig } from "@/types";
import { riskText } from "@/utils/risk";
import { formatDateTime } from "@/utils/dateTime";

const {
  errorMessage,
  filteredRules,
  keyword,
  loading,
  metrics,
  qualityGroups,
  rules,
  severityFilter,
  statusFilter,
  strategyPolicy,
  topRuleDocs,
  loadRules
} = useReviewRuleCatalog();

const {
  dialogVisible,
  editingRuleId,
  ruleForm,
  saving,
  statusSavingId,
  openCreateDialog,
  openEditDialog,
  saveRule,
  toggleRule
} = useReviewRuleEditor({ canManage, reloadRules: loadRules, rules });

const {
  strategySaving,
  strategyTargetMode,
  saveStrategyEnforcement
} = useReviewStrategyGovernance({ canManage, reloadRules: loadRules, strategyPolicy });

const {
  rollbackSavingId,
  ruleHistoryHasMore,
  ruleHistoryLoading,
  ruleVersionDialogVisible,
  ruleVersions,
  selectedRuleId,
  strategyHistoryHasMore,
  strategyHistoryLoading,
  strategyVersionDialogVisible,
  strategyVersions,
  cancelRuleHistoryRequest,
  cancelStrategyHistoryRequest,
  loadMoreRuleVersions,
  loadMoreStrategyVersions,
  openRuleVersions,
  openStrategyVersions,
  rollbackRuleVersion,
  rollbackStrategyVersion
} = useReviewPolicyHistory({ canManage, reloadRules: loadRules, rules, strategyPolicy });

const metricIconMap = {
  blue: ListChecks,
  red: ShieldAlert,
  orange: Zap,
  green: Target
} as const;

const getMetricIcon = useMetricIcon(metricIconMap, CheckCircle);

const ruleMetricItems = computed<MetricGridItem[]>(() =>
  metrics.value.map((metric) => ({
    label: metric.label,
    value: metric.value,
    note: metric.note,
    color: metric.color
  }))
);

const enforcementModeText = (mode: ReviewRuleConfig["enforcementMode"]) => {
  if (mode === "block") return "阻断";
  if (mode === "comment") return "评论";
  return "观察";
};

const percent = (value: number) => `${Number(value ?? 0).toFixed(1)}%`;

const qualityStatusType = (status: string): "success" | "warning" | "danger" | "info" => {
  if (status === "PASS") return "success";
  if (status === "ALERT") return "danger";
  if (status === "INSUFFICIENT_SAMPLE") return "warning";
  return "info";
};

onMounted(loadRules);
</script>
