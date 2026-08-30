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
        "src/features/**/composables/**/*.ts"
      ],
      exclude: [
        "src/**/*.test.ts",
        "src/api/generatedOpenApi.ts",
        "src/api/generatedOpenApiModels.ts"
      ],
      thresholds: {
        statements: 64,
        branches: 52,
        functions: 55,
        lines: 64
      }
    }
  }
});
