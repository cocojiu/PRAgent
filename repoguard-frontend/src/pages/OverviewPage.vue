<template>
  <div v-loading="loading" class="overview-page">
    <el-alert v-if="errorMessage" class="page-alert" type="error" :title="errorMessage" show-icon :closable="false" />

    <MetricGrid :metrics="overviewMetricItems" :resolve-icon="getMetricIcon" />

    <section class="dashboard-grid">
      <article class="dashboard-card chart-card chart-card--wide">
        <h2>审查趋势</h2>
        <EChartPanel v-if="reviewTrend.length" :option="trendOption" />
        <el-empty v-else description="暂无审查趋势数据" />
      </article>
      <article class="dashboard-card chart-card">
        <h2>风险分布</h2>
        <EChartPanel v-if="riskDistribution.length" :option="riskOption" />
        <el-empty v-else description="暂无风险分布数据" />
      </article>
      <article class="dashboard-card chart-card">
        <h2>规则命中</h2>
        <div v-if="ruleHits.length" class="donut-layout">
          <EChartPanel :option="ruleOption" />
          <ul class="rule-legend">
            <li v-for="rule in ruleHits" :key="rule.name">
              <span :style="{ background: rule.color }"></span>
              <b>{{ rule.name }}</b>
              <em>{{ rule.value }} ({{ rule.percent }})</em>
            </li>
          </ul>
        </div>
        <el-empty v-else description="暂无规则命中数据" />
      </article>
    </section>

    <section class="dashboard-grid llm-quality-grid">
      <article class="dashboard-card chart-card chart-card--wide">
        <div class="quality-card-head">
          <h2>LLM 质量趋势</h2>
          <el-segmented
            v-model="llmTrendDays"
            :options="llmTrendWindowOptions"
            size="small"
            :disabled="loading"
            @change="() => loadOverview()"
          />
        </div>
        <EChartPanel v-if="llmQualityTrend.length" :option="llmQualityTrendOption" />
        <el-empty v-else description="暂无 LLM 质量趋势数据" />
      </article>
      <article class="dashboard-card">
        <h2>模型质量</h2>
        <el-table :data="llmQualityByModel" class="rg-table" size="large" aria-label="模型质量统计">
          <el-table-column prop="model" label="模型" min-width="180" />
          <el-table-column prop="taskCount" label="任务" width="80" />
          <el-table-column prop="averageDuration" label="均耗时" width="100" />
          <el-table-column prop="averageTokens" label="均 Token" width="110" />
          <el-table-column prop="averageCost" label="均成本" width="120" />
          <el-table-column prop="parseSuccessRate" label="解析率" width="100" />
          <el-table-column prop="fallbackRate" label="兜底率" width="100" />
          <el-table-column prop="partialFallbackRate" label="补位率" width="100" />
          <el-table-column prop="validRate" label="有效率" width="100" />
          <el-table-column prop="falsePositiveRate" label="误报率" width="100" />
          <template #empty>
            <el-empty description="暂无模型质量数据" />
          </template>
        </el-table>
      </article>
      <article class="dashboard-card">
        <h2>仓库质量</h2>
        <el-table :data="llmQualityByRepository" class="rg-table" size="large" aria-label="仓库质量统计">
          <el-table-column prop="repository" label="仓库" min-width="180" />
          <el-table-column prop="taskCount" label="任务" width="80" />
          <el-table-column prop="fallbackRate" label="兜底率" width="100" />
          <el-table-column prop="partialFallbackRate" label="补位率" width="100" />
          <el-table-column prop="validRate" label="有效率" width="100" />
          <el-table-column prop="falsePositiveRate" label="误报率" width="100" />
          <template #empty>
            <el-empty description="暂无仓库质量数据" />
          </template>
        </el-table>
      </article>
    </section>

    <section class="bottom-grid">
      <article class="dashboard-card">
        <h2>近期高风险审查</h2>
        <el-table :data="highRiskReviews" class="rg-table" size="large" aria-label="近期高风险审查列表">
          <el-table-column prop="title" label="PR 标题" min-width="220" />
          <el-table-column prop="repository" label="仓库" width="150" />
          <el-table-column label="风险等级" width="110">
            <template #default="{ row }">
              <span :class="`risk-pill ${row.riskLevel}`">{{ riskText(row.riskLevel) }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="ruleHits" label="规则命中" width="100" />
          <el-table-column prop="reviewedAt" label="审查时间" width="170" />
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <span class="status-pill success">{{ row.status }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90">
            <template #default>
              <RouterLink class="table-link" :to="{ name: 'tasks' }">查看</RouterLink>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty description="暂无高风险审查" />
          </template>
        </el-table>
        <RouterLink class="card-footer-link" :to="{ name: 'tasks' }">查看更多</RouterLink>
      </article>

      <article class="dashboard-card">
        <h2>高频失败规则</h2>
        <el-table :data="failedRules" class="rg-table" size="large" aria-label="高频失败规则列表">
          <el-table-column prop="name" label="规则名称" min-width="180" />
          <el-table-column prop="count" label="命中次数" width="100" />
          <el-table-column label="趋势" width="100">
            <template #default="{ row }">
              <span :class="row.direction === 'up' ? 'trend danger' : 'trend'">
                {{ row.direction === "up" ? "上升" : "下降" }} {{ row.trend }}
              </span>
            </template>
          </el-table-column>
          <el-table-column prop="percent" label="占比" width="80" />
          <template #empty>
            <el-empty description="暂无失败规则统计" />
          </template>
        </el-table>
        <RouterLink class="card-footer-link" :to="{ name: 'rules' }">查看更多</RouterLink>
      </article>

      <article class="dashboard-card health-card">
        <h2>系统健康</h2>
        <div v-if="systemHealth.length" class="health-list">
          <div v-for="item in systemHealth" :key="item.name" class="health-item">
            <span>{{ item.name }}</span>
            <b>● {{ item.status }}</b>
          </div>
        </div>
        <el-empty v-else description="暂无健康检查数据" />
        <div class="health-footer">
          <span>最后检查：{{ lastHealthCheckAt }}</span>
          <button class="table-link" type="button" :disabled="loading" @click="loadOverview">刷新</button>
        </div>
      </article>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { RouterLink } from "vue-router";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import { Clock, FileText, ShieldAlert, Wallet } from "lucide-vue-next";
import EChartPanel from "@/components/EChartPanel.vue";
import MetricGrid, { type MetricGridItem } from "@/components/MetricGrid.vue";
import { fetchDashboardOverview } from "@/api/dashboard";
import { useMetricIcon } from "@/composables/useMetricIcon";
import { getErrorMessage } from "@/utils/errors";
import { riskText } from "@/utils/risk";
import type { DashboardOverview } from "@/types";
import {
  buildLlmQualityTrendOption,
  buildReviewTrendOption,
  buildRiskDistributionOption,
  buildRuleHitOption
} from "./overviewChartOptions";

const metricIconMap = {
  blue: FileText,
  red: ShieldAlert,
  green: Wallet,
  orange: Clock
} as const;

const getMetricIcon = useMetricIcon(metricIconMap, FileText);
const loading = ref(false);
const errorMessage = ref("");
const lastHealthCheckAt = ref("-");
const llmTrendDays = ref(7);
const llmTrendWindowOptions = [
  { label: "7 天", value: 7 },
  { label: "30 天", value: 30 },
  { label: "90 天", value: 90 }
];
const overview = ref<DashboardOverview>({
  overviewMetrics: [],
  reviewTrend: [],
  riskDistribution: [],
  ruleHits: [],
  highRiskReviews: [],
  failedRules: [],
  systemHealth: [],
  llmQualityByModel: [],
  llmQualityByRepository: [],
  llmQualityTrend: []
});

const overviewMetrics = computed(() => overview.value.overviewMetrics);
const reviewTrend = computed(() => overview.value.reviewTrend);
const riskDistribution = computed(() => overview.value.riskDistribution);
const ruleHits = computed(() => overview.value.ruleHits);
const highRiskReviews = computed(() => overview.value.highRiskReviews);
const failedRules = computed(() => overview.value.failedRules);
const systemHealth = computed(() => overview.value.systemHealth);
const llmQualityByModel = computed(() => overview.value.llmQualityByModel ?? []);
const llmQualityByRepository = computed(() => overview.value.llmQualityByRepository ?? []);
const llmQualityTrend = computed(() => overview.value.llmQualityTrend ?? []);

const overviewMetricItems = computed<MetricGridItem[]>(() =>
  overviewMetrics.value.map((metric) => ({
    label: metric.label,
    value: metric.value,
    color: metric.color,
    note: `较上周${metric.trendType.includes("up") ? "上升" : "下降"} ${metric.trend}`,
    noteClass: metric.trendType === "up-danger" ? "trend danger" : "trend"
  }))
);

const totalRuleHits = computed(() => ruleHits.value.reduce((total, item) => total + item.value, 0));

const loadOverview = async () => {
  loading.value = true;
  errorMessage.value = "";
  try {
    overview.value = await fetchDashboardOverview(llmTrendDays.value);
    lastHealthCheckAt.value = new Date().toLocaleString("zh-CN", { hour12: false });
  } catch (error) {
    errorMessage.value = getErrorMessage(error, "仪表盘数据加载失败");
    ElMessage.error(errorMessage.value);
  } finally {
    loading.value = false;
  }
};

onMounted(loadOverview);

const trendOption = computed(() => buildReviewTrendOption(reviewTrend.value));

const riskOption = computed(() => buildRiskDistributionOption(riskDistribution.value));

const ruleOption = computed(() => buildRuleHitOption(ruleHits.value, totalRuleHits.value));
const llmQualityTrendOption = computed(() => buildLlmQualityTrendOption(llmQualityTrend.value));
</script>
