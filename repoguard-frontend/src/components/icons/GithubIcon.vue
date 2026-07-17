<script setup lang="ts">
withDefaults(defineProps<{
  size?: number | string;
  color?: string;
  strokeWidth?: number | string;
}>(), {
  size: 24,
  color: "currentColor",
  strokeWidth: 2
});
</script>

<template>
  <svg
    xmlns="http://www.w3.org/2000/svg"
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    :stroke="color"
    :stroke-width="strokeWidth"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <path d="M15 22v-4a4.8 4.8 0 0 0-1-3.5c3.28-.36 6.72-1.61 6.72-7.25A5.65 5.65 0 0 0 19.22 3.3 5.4 5.4 0 0 0 19.13 0S17.95-.36 15 1.5a13.38 13.38 0 0 0-7 0C5.05-.36 3.87 0 3.87 0a5.4 5.4 0 0 0-.09 3.3 5.65 5.65 0 0 0-1.5 3.95c0 5.64 3.44 6.89 6.72 7.25A4.8 4.8 0 0 0 8 18v4" />
    <path d="M9 18c-4.51 2-5-2-7-2" />
  </svg>
</template>
