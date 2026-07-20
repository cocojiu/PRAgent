<template>
  <div ref="containerRef" class="deferred-chart-panel" :aria-busy="!activated">
    <AsyncEChartPanel
      v-if="activated"
      :accessible-label="accessibleLabel"
      :option="option"
      :summary="summary"
      @rendered="onChartRendered"
    />
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
import { defineAsyncComponent, onBeforeUnmount, onMounted, ref } from "vue";
import type { EChartsOption } from "echarts";
import {
  activateChartPerformanceTiming,
  beginChartPerformanceTiming,
  cancelChartPerformanceTiming,
  completeChartPerformanceTiming
} from "@/observability/frontendPerformanceDiagnosticsBridge";

const AsyncEChartPanel = defineAsyncComponent(() => import("./EChartPanel.vue"));

const props = defineProps<{
  accessibleLabel: string;
  option: EChartsOption;
  summary?: string;
}>();

const containerRef = ref<HTMLDivElement | null>(null);
const activated = ref(false);
let intersectionObserver: IntersectionObserver | null = null;
let idleCallbackHandle: number | undefined;
let fallbackTimer: ReturnType<typeof setTimeout> | undefined;
let chartRendered = false;

const renderChart = () => {
  idleCallbackHandle = undefined;
  fallbackTimer = undefined;
  activateChartPerformanceTiming(props.accessibleLabel);
  activated.value = true;
};

const onChartRendered = () => {
  chartRendered = true;
  completeChartPerformanceTiming(props.accessibleLabel);
};

const scheduleChartRender = () => {
  if (activated.value || idleCallbackHandle !== undefined || fallbackTimer !== undefined) {
    return;
  }
  intersectionObserver?.disconnect();
  intersectionObserver = null;
  if ("requestIdleCallback" in window) {
    idleCallbackHandle = window.requestIdleCallback(renderChart, { timeout: 1500 });
    return;
  }
  fallbackTimer = setTimeout(renderChart, 0);
};

onMounted(() => {
  beginChartPerformanceTiming(props.accessibleLabel);
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
  if (!chartRendered) {
    cancelChartPerformanceTiming(props.accessibleLabel);
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
