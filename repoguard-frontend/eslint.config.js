import js from "@eslint/js";
import pluginVue from "eslint-plugin-vue";
import tseslint from "typescript-eslint";

export default [
  {
    ignores: [
      "dist/**",
      "node_modules/**",
      "coverage/**"
    ]
  },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  ...pluginVue.configs["flat/recommended"],
  {
    files: ["src/**/*.{ts,vue}"],
    languageOptions: {
      parserOptions: {
        parser: tseslint.parser,
        ecmaVersion: "latest",
        sourceType: "module"
      }
    },
    rules: {
      "no-undef": "off",
      "vue/attributes-order": "off",
      "vue/html-self-closing": "off",
      "vue/max-attributes-per-line": "off",
      "vue/multiline-html-element-content-newline": "off",
      "vue/multi-word-component-names": "off",
      "vue/no-mutating-props": "off",
      "vue/no-v-html": "error",
      "vue/singleline-html-element-content-newline": "off",
      "no-restricted-imports": [
        "error",
        {
          paths: [
            {
              name: "echarts",
              allowTypeImports: true,
              message: "Runtime ECharts imports must stay behind EChartPanel. Type-only imports remain allowed."
            },
            {
              name: "echarts/core",
              message: "Use the centralized EChartPanel runtime; new ECharts runtime entry points require an explicit bundle-budget decision."
            },
            {
              name: "echarts/charts",
              message: "Use the centralized EChartPanel runtime; new chart modules require an explicit bundle-budget decision."
            },
            {
              name: "echarts/components",
              message: "Use the centralized EChartPanel runtime; new ECharts components require an explicit bundle-budget decision."
            },
            {
              name: "echarts/renderers",
              message: "Use the centralized EChartPanel runtime; renderer changes require an explicit bundle-budget decision."
            }
          ]
        }
      ],
      "@typescript-eslint/consistent-type-imports": ["error", { "prefer": "type-imports" }]
    }
  },
  {
    files: ["scripts/**/*.mjs"],
    languageOptions: {
      ecmaVersion: "latest",
      sourceType: "module",
      globals: {
        console: "readonly",
        process: "readonly"
      }
    },
    rules: {
      "no-undef": "error"
    }
  },
  {
    files: ["src/components/EChartPanel.vue"],
    rules: {
      "no-restricted-imports": [
        "error",
        {
          paths: [
            {
              name: "echarts",
              allowTypeImports: true,
              message: "EChartPanel may import public ECharts types only; keep runtime imports tree-shakeable."
            },
            {
              name: "echarts/charts",
              allowImportNames: ["BarChart", "LineChart", "PieChart"],
              message: "The chart-module surface is frozen; verify bundle and route performance before expanding it."
            },
            {
              name: "echarts/components",
              allowImportNames: ["AriaComponent", "GraphicComponent", "GridComponent", "LegendComponent", "TooltipComponent"],
              message: "The ECharts component surface is frozen; verify bundle and route performance before expanding it."
            },
            {
              name: "echarts/renderers",
              allowImportNames: ["CanvasRenderer"],
              message: "CanvasRenderer is the only approved renderer; verify bundle and route performance before changing it."
            }
          ]
        }
      ]
    }
  }
];
