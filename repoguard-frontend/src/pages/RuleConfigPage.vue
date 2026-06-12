<template>
  <div v-loading="loading" class="rules-page">
    <div class="page-heading page-heading-row">
      <div>
        <h1>规则配置</h1>
        <p>管理代码审查规则、严重级别和启用状态</p>
      </div>
      <el-button type="primary" size="large" :disabled="!canManage" @click="openCreateDialog">新增规则</el-button>
    </div>

    <el-alert v-if="errorMessage" class="page-alert" type="error" :title="errorMessage" show-icon :closable="false" />

    <MetricGrid :metrics="ruleMetricItems" :resolve-icon="getMetricIcon" />

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
          <el-table-column prop="updatedAt" label="最近更新" min-width="160" />
          <el-table-column label="操作" width="110" fixed="right">
            <template #default="{ row }">
              <el-button size="small" type="primary" plain :disabled="!canManage" @click="openEditDialog(row)">编辑</el-button>
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
        </div>
        <el-empty v-if="!topRuleDocs.length" description="暂无规则说明" />
      </aside>
    </section>

    <el-dialog v-model="dialogVisible" :title="editingRuleId ? '编辑规则' : '新增规则'" width="560px">
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
        <el-form-item label="规则说明">
          <el-input v-model="ruleForm.description" type="textarea" :rows="4" placeholder="描述规则命中的场景和建议" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!canManage" :loading="saving" @click="saveRule">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import { CheckCircle, ListChecks, Search, ShieldAlert, Target, Zap } from "lucide-vue-next";
import { canManage } from "@/stores/authState";
import {
  createReviewRule,
  fetchReviewRules,
  updateReviewRule,
  updateReviewRuleStatus
} from "@/api/config";
import MetricGrid, { type MetricGridItem } from "@/components/MetricGrid.vue";
import { useMetricIcon } from "@/composables/useMetricIcon";
import type { ReviewRuleConfig, ReviewRuleConfigRequest, RuleStatus, SimpleMetric } from "@/types";
import { getErrorMessage } from "@/utils/errors";
import { riskText } from "@/utils/risk";

const severityFilter = ref("");
const statusFilter = ref("");
const keyword = ref("");
const loading = ref(false);
const saving = ref(false);
const statusSavingId = ref("");
const errorMessage = ref("");
const dialogVisible = ref(false);
const editingRuleId = ref("");
const rules = ref<ReviewRuleConfig[]>([]);
const metrics = ref<SimpleMetric[]>([]);

const ruleForm = reactive<ReviewRuleConfigRequest>({
  id: "",
  name: "",
  scope: "",
  severity: "low",
  status: "disabled",
  confidence: 90,
  description: ""
});

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

const filteredRules = computed(() => {
  const query = keyword.value.trim().toLowerCase();
  return rules.value.filter((rule) => {
    const matchesSeverity = !severityFilter.value || rule.severity === severityFilter.value;
    const matchesStatus = !statusFilter.value || rule.status === statusFilter.value;
    const matchesKeyword =
      !query || rule.id.toLowerCase().includes(query) || rule.name.toLowerCase().includes(query) || rule.scope.toLowerCase().includes(query);
    return matchesSeverity && matchesStatus && matchesKeyword;
  });
});

const topRuleDocs = computed(() => rules.value.slice(0, 4));

const loadRules = async () => {
  loading.value = true;
  errorMessage.value = "";
  try {
    const response = await fetchReviewRules();
    metrics.value = response.metrics;
    rules.value = response.rules;
  } catch (error) {
    errorMessage.value = getErrorMessage(error, "规则加载失败");
    ElMessage.error(errorMessage.value);
  } finally {
    loading.value = false;
  }
};

const resetForm = (rule?: ReviewRuleConfig) => {
  ruleForm.id = rule?.id ?? "";
  ruleForm.name = rule?.name ?? "";
  ruleForm.scope = rule?.scope ?? "Java Patch";
  ruleForm.severity = rule?.severity ?? "low";
  ruleForm.status = rule?.status ?? "disabled";
  ruleForm.confidence = Number.parseInt(rule?.confidence ?? "90", 10);
  ruleForm.description = rule?.description ?? "";
};

const openCreateDialog = () => {
  if (!canManage.value || saving.value) {
    return;
  }
  editingRuleId.value = "";
  resetForm();
  dialogVisible.value = true;
};

const openEditDialog = (rule: ReviewRuleConfig) => {
  if (!canManage.value) {
    return;
  }
  editingRuleId.value = rule.id;
  resetForm(rule);
  dialogVisible.value = true;
};

const saveRule = async () => {
  if (!canManage.value) {
    return;
  }
  const validationMessage = validateRuleForm();
  if (validationMessage) {
    ElMessage.warning(validationMessage);
    return;
  }
  saving.value = true;
  try {
    const payload = normalizedPayload();
    if (editingRuleId.value) {
      await updateReviewRule(editingRuleId.value, payload);
      ElMessage.success("规则已更新");
    } else {
      await createReviewRule(payload);
      ElMessage.success("规则已创建");
    }
    dialogVisible.value = false;
    await loadRules();
  } catch (error) {
    ElMessage.error(getErrorMessage(error, "规则操作失败"));
  } finally {
    saving.value = false;
  }
};

const toggleRule = async (rule: ReviewRuleConfig, value: string | number | boolean) => {
  if (!canManage.value || statusSavingId.value) {
    rule.status = value === "enabled" ? "disabled" : "enabled";
    return;
  }
  const nextStatus = value === "enabled" ? "enabled" : "disabled";
  const previousStatus: RuleStatus = nextStatus === "enabled" ? "disabled" : "enabled";
  statusSavingId.value = rule.id;
  try {
    const updated = await updateReviewRuleStatus(rule.id, { status: nextStatus });
    Object.assign(rule, updated);
    ElMessage.success(`${rule.name} 已${nextStatus === "enabled" ? "启用" : "停用"}`);
    await loadRules();
  } catch (error) {
    rule.status = previousStatus;
    ElMessage.error(getErrorMessage(error, "规则操作失败"));
  } finally {
    statusSavingId.value = "";
  }
};

const validateRuleForm = () => {
  if (!ruleForm.id.trim()) {
    return "请输入规则 ID";
  }
  if (!/^[A-Za-z0-9_-]+$/.test(ruleForm.id.trim())) {
    return "规则 ID 只能包含字母、数字、下划线和连字符";
  }
  if (!ruleForm.name.trim()) {
    return "请输入规则名称";
  }
  if (!ruleForm.scope.trim()) {
    return "请输入适用范围";
  }
  if (!ruleForm.description.trim()) {
    return "请输入规则说明";
  }
  return "";
};

const normalizedPayload = (): ReviewRuleConfigRequest => ({
  id: ruleForm.id.trim().toUpperCase(),
  name: ruleForm.name.trim(),
  scope: ruleForm.scope.trim(),
  severity: ruleForm.severity,
  status: ruleForm.status,
  confidence: ruleForm.confidence,
  description: ruleForm.description.trim()
});

onMounted(loadRules);
</script>
