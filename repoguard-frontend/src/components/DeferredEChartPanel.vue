<template>
  <div ref="containerRef" class="deferred-chart-panel" :aria-busy="!chartRendered && !chartLoadFailed">
    <component
      :is="chartComponent"
      v-if="chartComponent"
      :accessible-label="accessibleLabel"
      :option="option"
      :summary="summary"
      @rendered="onChartRendered"
    />
    <div
      v-else-if="chartLoadFailed"
      class="chart-panel deferred-chart-error"
      role="alert"
    >
      <span>图表加载失败</span>
      <button type="button" @click="retryChartRender">重试</button>
    </div>
    <div
      v-else
      class="chart-panel deferred-chart-placeholder"
      role="status"
      :aria-label="`${accessibleLabel}正在准备`"
    >
      <span class="deferred-chart-status">图表加载中</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, shallowRef } from "vue";
import type { Component } from "vue";
import type { EChartsOption } from "echarts";
import {
  activateChartPerformanceTiming,
  beginChartPerformanceTiming,
  cancelChartPerformanceTiming,
  completeChartPerformanceTiming
} from "@/observability/frontendPerformanceDiagnosticsBridge";

const props = defineProps<{
  accessibleLabel: string;
  option: EChartsOption;
  summary?: string;
}>();

const containerRef = ref<HTMLDivElement | null>(null);
const chartComponent = shallowRef<Component>();
const chartLoadFailed = ref(false);
let intersectionObserver: IntersectionObserver | null = null;
let idleCallbackHandle: number | undefined;
let fallbackTimer: ReturnType<typeof setTimeout> | undefined;
let chartLoading = false;
const chartRendered = ref(false);
let chartTimingActive = false;
let unmounted = false;

const renderChart = async () => {
  idleCallbackHandle = undefined;
  fallbackTimer = undefined;
  if (chartLoading || chartComponent.value || unmounted) {
    return;
  }
  chartLoading = true;
  activateChartPerformanceTiming(props.accessibleLabel);
  try {
    const module = await import("./EChartPanel.vue");
    if (!unmounted) {
      chartComponent.value = module.default;
    }
  } catch {
    if (!unmounted) {
      chartLoadFailed.value = true;
    }
    if (chartTimingActive) {
      cancelChartPerformanceTiming(props.accessibleLabel);
      chartTimingActive = false;
    }
  } finally {
    chartLoading = false;
  }
};

const onChartRendered = () => {
  chartRendered.value = true;
  if (chartTimingActive) {
    completeChartPerformanceTiming(props.accessibleLabel);
    chartTimingActive = false;
  }
};

const scheduleChartRender = () => {
  if (chartComponent.value || chartLoading || idleCallbackHandle !== undefined || fallbackTimer !== undefined) {
    return;
  }
  intersectionObserver?.disconnect();
  intersectionObserver = null;
  if ("requestIdleCallback" in window) {
    idleCallbackHandle = window.requestIdleCallback(() => void renderChart(), { timeout: 1500 });
    return;
  }
  fallbackTimer = setTimeout(() => void renderChart(), 0);
};

const retryChartRender = () => {
  if (chartLoading) {
    return;
  }
  chartLoadFailed.value = false;
  chartTimingActive = true;
  beginChartPerformanceTiming(props.accessibleLabel);
  void renderChart();
};

onMounted(() => {
  beginChartPerformanceTiming(props.accessibleLabel);
  chartTimingActive = true;
  if (typeof IntersectionObserver === "undefined" || !containerRef.value) {
    scheduleChartRender();
    return;
  }
  intersectionObserver = new IntersectionObserver(
    (entries) => {
      if (entries.some((entry) => entry.isIntersecting)) {
        scheduleChartRender();
      }
    },
    { rootMargin: "240px 0px" }
  );
  intersectionObserver.observe(containerRef.value);
});

onBeforeUnmount(() => {
  unmounted = true;
  if (chartTimingActive && !chartRendered.value) {
    cancelChartPerformanceTiming(props.accessibleLabel);
    chartTimingActive = false;
  }
  intersectionObserver?.disconnect();
  intersectionObserver = null;
  if (idleCallbackHandle !== undefined && "cancelIdleCallback" in window) {
    window.cancelIdleCallback(idleCallbackHandle);
    idleCallbackHandle = undefined;
  }
  if (fallbackTimer !== undefined) {
    clearTimeout(fallbackTimer);
    fallbackTimer = undefined;
  }
});
</script>

<style scoped>
.deferred-chart-panel {
  width: 100%;
  min-height: 300px;
  min-width: 0;
}

.deferred-chart-placeholder {
  position: relative;
  overflow: hidden;
  border-radius: 8px;
  background: linear-gradient(110deg, #f6f8fc 8%, #edf2f8 18%, #f6f8fc 33%);
  background-size: 200% 100%;
  animation: deferred-chart-shimmer 1.4s linear infinite;
}

.deferred-chart-status {
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

.deferred-chart-error {
  display: grid;
  place-content: center;
  gap: 12px;
  border-radius: 8px;
  background: #f8fafc;
  color: #64748b;
  text-align: center;
}

.deferred-chart-error button {
  min-height: 34px;
  padding: 0 16px;
  border: 1px solid #bfdbfe;
  border-radius: 8px;
  background: #eff6ff;
  color: #1268ff;
  cursor: pointer;
  font: inherit;
  font-weight: 700;
}

@keyframes deferred-chart-shimmer {
  to {
    background-position-x: -200%;
  }
}

@media (prefers-reduced-motion: reduce) {
  .deferred-chart-placeholder {
    animation: none;
  }
}
</style>
