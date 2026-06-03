<template>
  <div class="rules-page">
    <div class="page-heading page-heading-row">
      <div>
        <h1>规则配置</h1>
        <p>管理代码审查规则、严重级别和启用状态</p>
      </div>
      <el-button type="primary" size="large" @click="createRule">新增规则</el-button>
    </div>

    <MetricGrid :metrics="ruleMetricItems" :resolve-icon="getMetricIcon" />

    <section class="rule-layout">
      <article class="rule-panel">
        <div class="filter-bar rule-filter">
          <el-select v-model="severityFilter" placeholder="全部严重级别" clearable>
            <el-option label="全部严重级别" value="" />
            <el-option label="高风险" value="high" />
            <el-option label="中风险" value="medium" />
            <el-option label="低风险" value="low" />
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
              <el-switch v-model="row.status" active-value="enabled" inactive-value="disabled" @change="toggleRule(row.name)" />
            </template>
          </el-table-column>
          <el-table-column prop="hitCount" label="命中次数" width="110" />
          <el-table-column prop="confidence" label="置信度" width="100" />
          <el-table-column prop="updatedAt" label="最近更新" min-width="160" />
          <el-table-column label="操作" width="110" fixed="right">
            <template #default="{ row }">
              <el-button size="small" type="primary" plain @click="editRule(row.name)">编辑</el-button>
            </template>
          </el-table-column>
        </el-table>
      </article>

      <aside class="dashboard-card rule-doc-card">
        <h2>规则说明</h2>
        <div v-for="rule in topRuleDocs" :key="rule.id" class="rule-doc-item">
          <strong>{{ rule.id }}</strong>
          <p>{{ rule.description }}</p>
        </div>
      </aside>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import { CheckCircle, ListChecks, Search, ShieldAlert, Target, Zap } from "lucide-vue-next";
import MetricGrid, { type MetricGridItem } from "@/components/MetricGrid.vue";
import { useMetricIcon } from "@/composables/useMetricIcon";
import { reviewRules, ruleMetrics } from "@/mocks/rules";
import { riskText } from "@/utils/risk";

const severityFilter = ref("");
const statusFilter = ref("");
const keyword = ref("");
const localRules = reactive(reviewRules.map((rule) => ({ ...rule })));

const metricIconMap = {
  blue: ListChecks,
  red: ShieldAlert,
  orange: Zap,
  green: Target
} as const;

const getMetricIcon = useMetricIcon(metricIconMap, CheckCircle);

const ruleMetricItems = computed<MetricGridItem[]>(() =>
  ruleMetrics.map((metric) => ({
    label: metric.label,
    value: metric.value,
    note: metric.note,
    color: metric.color
  }))
);

const filteredRules = computed(() => {
  const query = keyword.value.trim().toLowerCase();
  return localRules.filter((rule) => {
    const matchesSeverity = !severityFilter.value || rule.severity === severityFilter.value;
    const matchesStatus = !statusFilter.value || rule.status === statusFilter.value;
    const matchesKeyword =
      !query || rule.id.toLowerCase().includes(query) || rule.name.toLowerCase().includes(query) || rule.scope.toLowerCase().includes(query);
    return matchesSeverity && matchesStatus && matchesKeyword;
  });
});

const topRuleDocs = computed(() => localRules.slice(0, 4));

const toggleRule = (name: string) => ElMessage.success(`${name} 状态已更新`);
const editRule = (name: string) => ElMessage.info(`编辑 ${name} 功能暂未接入后端`);
const createRule = () => ElMessage.info("新增规则功能暂未接入后端");
</script>
