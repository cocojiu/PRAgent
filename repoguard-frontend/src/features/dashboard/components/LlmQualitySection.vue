<template>
  <section class="dashboard-grid llm-quality-grid">
    <article class="dashboard-card chart-card chart-card--wide">
      <div class="quality-card-head">
        <h2>LLM 质量趋势</h2>
        <el-segmented
          :model-value="trendDays"
          :options="trendWindowOptions"
          size="small"
          :disabled="loading"
          @update:model-value="onTrendDaysChange"
        />
      </div>
      <EChartPanel
        v-if="qualityTrend.length"
        accessible-label="LLM 质量趋势图"
        :option="qualityTrendOption"
        :summary="qualityTrendSummary"
      />
      <el-empty v-else description="暂无 LLM 质量趋势数据" />
    </article>
    <article class="dashboard-card">
      <h2>模型质量</h2>
      <el-table :data="qualityByModel" class="rg-table" size="large" max-height="360" aria-label="模型质量统计">
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
      <el-table :data="qualityByRepository" class="rg-table" size="large" max-height="360" aria-label="仓库质量统计">
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
</template>

<script setup lang="ts">
import { computed } from "vue";
import type { EChartsOption } from "echarts";
import EChartPanel from "@/components/EChartPanel.vue";
import type { LlmQualityByModel, LlmQualityByRepository, LlmQualityTrendPoint } from "@/types";

interface TrendWindowOption {
  label: string;
  value: number;
}

const props = defineProps<{
  loading: boolean;
  trendDays: number;
  trendWindowOptions: TrendWindowOption[];
  qualityTrend: LlmQualityTrendPoint[];
  qualityTrendOption: EChartsOption;
  qualityByModel: LlmQualityByModel[];
  qualityByRepository: LlmQualityByRepository[];
}>();

const qualityTrendSummary = computed(() =>
  props.qualityTrend
    .map((item) =>
      `${item.date} ${item.taskCount} 个任务，解析率 ${item.parseSuccessRate}，兜底率 ${item.fallbackRate}，部分补位率 ${item.partialFallbackRate}`
    )
    .join("；")
);

const emit = defineEmits<{
  trendDaysChange: [value: number];
}>();

const onTrendDaysChange = (value: string | number | boolean) => {
  emit("trendDaysChange", Number(value));
};
</script>
