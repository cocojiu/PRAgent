<template>
  <section class="metric-grid" :aria-busy="loading">
    <template v-if="loading && metrics.length === 0">
      <div
        v-for="index in skeletonCount"
        :key="`metric-skeleton-${index}`"
        class="metric-card metric-card--skeleton"
        aria-hidden="true"
      >
        <span class="metric-skeleton-icon"></span>
        <span class="metric-skeleton-copy">
          <span class="metric-skeleton-line metric-skeleton-title"></span>
          <span class="metric-skeleton-line metric-skeleton-value"></span>
          <span class="metric-skeleton-line metric-skeleton-note"></span>
        </span>
      </div>
    </template>
    <div v-for="metric in metrics" :key="metric.label" class="metric-card">
      <div class="metric-icon" :class="`metric-icon--${metric.color}`">
        <component :is="resolveIcon(metric.color)" :size="30" />
      </div>
      <div>
        <p>{{ metric.label }}</p>
        <strong>{{ metric.value }}</strong>
        <span :class="metric.noteClass ?? 'trend'">{{ metric.note }}</span>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import type { Component } from "vue";

export interface MetricGridItem {
  label: string;
  value: string;
  note: string;
  noteClass?: string;
  color: string;
}

withDefaults(defineProps<{
  metrics: MetricGridItem[];
  resolveIcon: (color: string) => Component;
  loading?: boolean;
  skeletonCount?: number;
}>(), {
  loading: false,
  skeletonCount: 4
});
</script>
