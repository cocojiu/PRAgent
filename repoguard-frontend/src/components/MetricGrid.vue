<template>
  <section class="metric-grid">
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

defineProps<{
  metrics: MetricGridItem[];
  resolveIcon: (color: string) => Component;
}>();
</script>
