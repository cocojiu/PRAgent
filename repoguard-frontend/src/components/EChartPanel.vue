<template>
  <div ref="chartRef" class="chart-panel"></div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from "vue";
import * as echarts from "echarts/core";
import { BarChart, LineChart, PieChart } from "echarts/charts";
import { GraphicComponent, GridComponent, LegendComponent, TooltipComponent } from "echarts/components";
import { CanvasRenderer } from "echarts/renderers";
import type { EChartsOption } from "echarts";

echarts.use([BarChart, LineChart, PieChart, GraphicComponent, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer]);

const props = defineProps<{
  option: EChartsOption;
}>();

const chartRef = ref<HTMLDivElement | null>(null);
let chart: echarts.EChartsType | null = null;

const renderChart = () => {
  if (!chartRef.value) return;
  chart ??= echarts.init(chartRef.value);
  chart.setOption(props.option);
};

const resize = () => chart?.resize();

onMounted(() => {
  renderChart();
  window.addEventListener("resize", resize);
});

watch(() => props.option, renderChart);

onBeforeUnmount(() => {
  window.removeEventListener("resize", resize);
  chart?.dispose();
  chart = null;
});
</script>
