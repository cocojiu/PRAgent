import { fileURLToPath, URL } from "node:url";
import type { GetManualChunk } from "rollup";
import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

const manualChunks: GetManualChunk = (id) => {
  const normalizedId = id.replace(/\\/g, "/");
  if (!normalizedId.includes("/node_modules/")) {
    return undefined;
  }

  if (normalizedId.includes("/node_modules/zrender/")) {
    return "zrender";
  }

  if (normalizedId.includes("/node_modules/echarts/")) {
    if (normalizedId.includes("/echarts/charts/")) {
      return "echarts-charts";
    }
    if (normalizedId.includes("/echarts/components/")) {
      return "echarts-components";
    }
    if (normalizedId.includes("/echarts/renderers/")) {
      return "echarts-renderers";
    }
    return "echarts-core";
  }

  if (normalizedId.includes("/node_modules/element-plus/")) {
    return "element-plus";
  }

  if (
    normalizedId.includes("/node_modules/vue/")
      || normalizedId.includes("/node_modules/@vue/")
      || normalizedId.includes("/node_modules/vue-router/")
      || normalizedId.includes("/node_modules/pinia/")
  ) {
    return "vue-vendor";
  }

  return "vendor";
};

export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      "/api": {
        target: "http://localhost:8081",
        changeOrigin: true
      }
    }
  },
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url))
    }
  },
  build: {
    rollupOptions: {
      output: {
        manualChunks
      }
    }
  }
});
