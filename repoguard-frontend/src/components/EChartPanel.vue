<template>
  <div
    ref="chartRef"
    class="chart-panel"
    role="img"
    :aria-label="accessibleLabel"
    :aria-describedby="summary ? summaryId : undefined"
  ></div>
  <p v-if="summary" :id="summaryId" class="chart-panel-summary">{{ summary }}</p>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, useId, watch } from "vue";
import * as echarts from "echarts/core";
import { BarChart, LineChart, PieChart } from "echarts/charts";
import { AriaComponent, GraphicComponent, GridComponent, LegendComponent, TooltipComponent } from "echarts/components";
import { CanvasRenderer } from "echarts/renderers";
import type { EChartsOption } from "echarts";

echarts.use([
  AriaComponent,
  BarChart,
  LineChart,
  PieChart,
  GraphicComponent,
  GridComponent,
  LegendComponent,
  TooltipComponent,
  CanvasRenderer
]);

const props = defineProps<{
  accessibleLabel: string;
  option: EChartsOption;
  summary?: string;
}>();
const emit = defineEmits<{
  rendered: [];
}>();

const chartRef = ref<HTMLDivElement | null>(null);
const summaryId = `chart-summary-${useId()}`;
let chart: echarts.EChartsType | null = null;
let resizeObserver: ResizeObserver | null = null;
let usingWindowResizeFallback = false;
let rendered = false;

const notifyRendered = () => {
  if (rendered) {
    return;
  }
  rendered = true;
  emit("rendered");
};

const renderChart = () => {
  if (!chartRef.value) return;
  if (!chart) {
    chart = echarts.init(chartRef.value);
    chart.on("finished", notifyRendered);
  }
  chart.setOption({
    ...props.option,
    aria: {
      enabled: true,
      description: props.summary ? `${props.accessibleLabel}。${props.summary}` : props.accessibleLabel,
      decal: { show: true }
    }
  });
};

const resize = () => chart?.resize();

onMounted(() => {
  renderChart();
  if (typeof ResizeObserver === "undefined" || !chartRef.value) {
    usingWindowResizeFallback = true;
    window.addEventListener("resize", resize);
    return;
  }
  resizeObserver = new ResizeObserver(resize);
  resizeObserver.observe(chartRef.value);
});

watch(() => props.option, renderChart);

onBeforeUnmount(() => {
  resizeObserver?.disconnect();
  resizeObserver = null;
  if (usingWindowResizeFallback) {
    window.removeEventListener("resize", resize);
    usingWindowResizeFallback = false;
  }
  chart?.off("finished", notifyRendered);
  chart?.dispose();
  chart = null;
});
</script>

<style scoped>
.chart-panel-summary {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}
</style>
