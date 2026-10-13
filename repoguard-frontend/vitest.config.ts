import { fileURLToPath, URL } from "node:url";
import { defineConfig } from "vitest/config";
import vue from "@vitejs/plugin-vue";

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url))
    }
  },
  test: {
    environment: "jsdom",
    globals: false,
    include: ["src/**/*.test.ts"],
    coverage: {
      provider: "v8",
      reporter: ["text", "json-summary", "html"],
      reportsDirectory: "coverage",
      include: [
        "src/api/**/*.ts",
        "src/stores/**/*.ts",
        "src/router/**/*.ts",
        "src/composables/**/*.ts",
        "src/features/**/composables/**/*.ts",
        "src/features/review-detail/**/*.ts",
        "src/utils/**/*.ts",
        "src/observability/**/*.ts",
        "src/pages/RuleConfigPage.vue",
        "src/pages/IntegrationsPage.vue",
        "src/pages/UserManagementPage.vue",
        "src/pages/NotificationOpsPage.vue"
      ],
      exclude: [
        "src/**/*.test.ts",
        "src/api/generated/**"
      ],
      thresholds: {
        statements: 75,
        branches: 62,
        functions: 65,
        lines: 75
      }
    }
  }
});
