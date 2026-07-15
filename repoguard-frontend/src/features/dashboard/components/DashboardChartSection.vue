<template>
  <section class="dashboard-grid">
    <article class="dashboard-card chart-card chart-card--wide">
      <h2>审查趋势</h2>
      <EChartPanel
        v-if="reviewTrend.length"
        accessible-label="审查趋势图"
        :option="trendOption"
        :summary="trendSummary"
      />
      <el-empty v-else description="暂无审查趋势数据" />
    </article>
    <article class="dashboard-card chart-card">
      <h2>风险分布</h2>
      <EChartPanel
        v-if="riskDistribution.length"
        accessible-label="风险分布图"
        :option="riskOption"
        :summary="riskSummary"
      />
      <el-empty v-else description="暂无风险分布数据" />
    </article>
    <article class="dashboard-card chart-card">
      <h2>规则命中</h2>
      <div v-if="ruleHits.length" class="donut-layout">
        <EChartPanel accessible-label="规则命中分布图" :option="ruleOption" :summary="ruleSummary" />
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
</template>

<script setup lang="ts">
import { computed } from "vue";
import type { EChartsOption } from "echarts";
import EChartPanel from "@/components/EChartPanel.vue";
import type { ChartSlice, ReviewTrendPoint } from "@/types";

const props = defineProps<{
  reviewTrend: ReviewTrendPoint[];
  riskDistribution: ChartSlice[];
  ruleHits: Required<ChartSlice>[];
  trendOption: EChartsOption;
  riskOption: EChartsOption;
  ruleOption: EChartsOption;
}>();

const trendSummary = computed(() =>
  props.reviewTrend.map((item) => `${item.date} ${item.value} 次`).join("；")
);
const riskSummary = computed(() =>
  props.riskDistribution.map((item) => `${item.name} ${item.value} 项`).join("；")
);
const ruleSummary = computed(() =>
  props.ruleHits.map((item) => `${item.name} ${item.value} 次，占 ${item.percent}`).join("；")
);
</script>
