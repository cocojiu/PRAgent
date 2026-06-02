<template>
  <div ref="chartRef" class="chart-panel"></div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from "vue";
import * as echarts from "echarts";
import type { EChartsOption } from "echarts";

const props = defineProps<{
  option: EChartsOption;
}>();

const chartRef = ref<HTMLDivElement | null>(null);
let chart: echarts.ECharts | null = null;

const renderChart = () => {
  if (!chartRef.value) return;
  chart ??= echarts.init(chartRef.value);
  chart.setOption(props.option, true);
};

const resize = () => chart?.resize();

onMounted(() => {
  renderChart();
  window.addEventListener("resize", resize);
});

watch(() => props.option, renderChart, { deep: true });

onBeforeUnmount(() => {
  window.removeEventListener("resize", resize);
  chart?.dispose();
  chart = null;
});
</script>

