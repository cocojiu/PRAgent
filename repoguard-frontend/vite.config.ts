import { fileURLToPath, URL } from "node:url";
import { gzipSync } from "node:zlib";
import { defineConfig, type Plugin } from "vite";
import vue from "@vitejs/plugin-vue";
import Components from "unplugin-vue-components/vite";
import { ElementPlusResolver } from "unplugin-vue-components/resolvers";

const KIB = 1024;
const BUNDLE_BUDGET_WARNING_RATIO = 0.85;
const BUNDLE_BUDGET_MINIMUM_HEADROOM_PERCENT = Math.round((1 - BUNDLE_BUDGET_WARNING_RATIO) * 100);

const bundleBudgets = {
  initialJavaScriptGzip: 150 * KIB,
  initialCssGzip: 24 * KIB,
  maxAsyncJavaScriptGzip: 140 * KIB,
  overviewRouteJavaScriptGzip: 60 * KIB,
  overviewRouteCssGzip: 12 * KIB,
  overviewRouteRequests: 16
} as const;

type ViteChunkMetadata = {
  importedCss?: Set<string>;
};

const gzipSize = (source: string | Uint8Array) => gzipSync(source).byteLength;

const formatKiB = (bytes: number) => `${(bytes / KIB).toFixed(1)} KiB`;

const bundleBudgetPlugin = (): Plugin => ({
  name: "repoguard-bundle-budget",
  generateBundle(_outputOptions, bundle) {
    const chunks = Object.values(bundle).filter((item) => item.type === "chunk");
    const chunkByFileName = new Map(chunks.map((chunk) => [chunk.fileName, chunk]));
    const initialChunkNames = new Set<string>();

    const collectStaticChunk = (fileName: string, chunkNames: Set<string>) => {
      if (chunkNames.has(fileName)) {
        return;
      }

      const chunk = chunkByFileName.get(fileName);
      if (!chunk) {
        return;
      }

      chunkNames.add(fileName);
      chunk.imports.forEach((importedFileName) => collectStaticChunk(importedFileName, chunkNames));
    };

    chunks
      .filter((chunk) => chunk.isEntry)
      .forEach((chunk) => collectStaticChunk(chunk.fileName, initialChunkNames));

    if (initialChunkNames.size === 0) {
      this.error("[bundle-budget] No entry chunk was found; bundle budgets cannot be evaluated.");
    }

    const initialCssNames = new Set<string>();
    let initialJavaScriptGzip = 0;
    for (const fileName of initialChunkNames) {
      const chunk = chunkByFileName.get(fileName);
      if (!chunk) {
        continue;
      }

      initialJavaScriptGzip += gzipSize(chunk.code);
      const metadata = (chunk as typeof chunk & { viteMetadata?: ViteChunkMetadata }).viteMetadata;
      metadata?.importedCss?.forEach((cssFileName) => initialCssNames.add(cssFileName));
    }

    let initialCssGzip = 0;
    for (const fileName of initialCssNames) {
      const asset = bundle[fileName];
      if (asset?.type === "asset") {
        initialCssGzip += gzipSize(asset.source);
      }
    }

    const overviewRouteChunk = chunks.find((chunk) =>
      chunk.facadeModuleId?.replaceAll("\\", "/").endsWith("/src/pages/OverviewPage.vue")
    );
    if (!overviewRouteChunk) {
      this.error("[bundle-budget] Overview route chunk was not found; route budgets cannot be evaluated.");
    }
    const overviewRouteChunkNames = new Set<string>();
    collectStaticChunk(overviewRouteChunk.fileName, overviewRouteChunkNames);
    const overviewRouteIncrementalChunkNames = new Set(
      [...overviewRouteChunkNames].filter((fileName) => !initialChunkNames.has(fileName))
    );
    const overviewRouteCssNames = new Set<string>();
    for (const fileName of overviewRouteChunkNames) {
      const chunk = chunkByFileName.get(fileName);
      const metadata = chunk
        ? (chunk as typeof chunk & { viteMetadata?: ViteChunkMetadata }).viteMetadata
        : undefined;
      metadata?.importedCss?.forEach((cssFileName) => overviewRouteCssNames.add(cssFileName));
    }
    const overviewRouteIncrementalCssNames = new Set(
      [...overviewRouteCssNames].filter((fileName) => !initialCssNames.has(fileName))
    );
    const overviewRouteJavaScriptGzip = [...overviewRouteIncrementalChunkNames].reduce(
      (total, fileName) => total + gzipSize(chunkByFileName.get(fileName)?.code ?? ""),
      0
    );
    const overviewRouteCssGzip = [...overviewRouteIncrementalCssNames].reduce((total, fileName) => {
      const asset = bundle[fileName];
      return total + (asset?.type === "asset" ? gzipSize(asset.source) : 0);
    }, 0);
    const overviewRouteRequests =
      overviewRouteIncrementalChunkNames.size + overviewRouteIncrementalCssNames.size;

    const asyncJavaScriptChunks = chunks
      .filter((chunk) => !initialChunkNames.has(chunk.fileName))
      .map((chunk) => ({ fileName: chunk.fileName, gzipBytes: gzipSize(chunk.code) }))
      .sort((left, right) => right.gzipBytes - left.gzipBytes);
    const largestAsyncChunk = asyncJavaScriptChunks[0] ?? { fileName: "none", gzipBytes: 0 };

    const measurements = [
      {
        label: "initial JavaScript gzip",
        actual: initialJavaScriptGzip,
        budget: bundleBudgets.initialJavaScriptGzip,
        format: formatKiB
      },
      {
        label: "initial CSS gzip",
        actual: initialCssGzip,
        budget: bundleBudgets.initialCssGzip,
        format: formatKiB
      },
      {
        label: `largest async JavaScript gzip (${largestAsyncChunk.fileName})`,
        actual: largestAsyncChunk.gzipBytes,
        budget: bundleBudgets.maxAsyncJavaScriptGzip,
        format: formatKiB
      },
      {
        label: "overview route critical JavaScript gzip",
        actual: overviewRouteJavaScriptGzip,
        budget: bundleBudgets.overviewRouteJavaScriptGzip,
        format: formatKiB
      },
      {
        label: "overview route critical CSS gzip",
        actual: overviewRouteCssGzip,
        budget: bundleBudgets.overviewRouteCssGzip,
        format: formatKiB
      },
      {
        label: "overview route critical requests",
        actual: overviewRouteRequests,
        budget: bundleBudgets.overviewRouteRequests,
        format: String
      }
    ];
    const summary = measurements
      .map(({ label, actual, budget, format }) => `${label}: ${format(actual)} / ${format(budget)}`)
      .join("; ");
    const violations = measurements.filter(({ actual, budget }) => actual > budget);

    if (violations.length > 0) {
      this.error(`[bundle-budget] Budget exceeded. ${summary}`);
    }

    const lowHeadroomMeasurements = measurements.filter(
      ({ actual, budget }) => actual >= budget * BUNDLE_BUDGET_WARNING_RATIO
    );
    if (lowHeadroomMeasurements.length > 0) {
      const warningSummary = lowHeadroomMeasurements
        .map(({ label, actual, budget, format }) => `${label}: ${format(actual)} / ${format(budget)}`)
        .join("; ");
      this.warn(
        `[bundle-budget] Headroom below ${BUNDLE_BUDGET_MINIMUM_HEADROOM_PERCENT}%. ${warningSummary}`
      );
    }

    this.info(`[bundle-budget] ${summary}`);
  }
});

export default defineConfig({
  plugins: [
    vue(),
    Components({
      dts: false,
      resolvers: [
        ElementPlusResolver({
          importStyle: "css"
        })
      ]
    }),
    bundleBudgetPlugin()
  ],
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
        codeSplitting: {
          groups: [
            {
              name: "zrender",
              test: /node_modules[\\/]zrender[\\/]/,
              priority: 30
            },
            {
              name: "echarts",
              test: /node_modules[\\/]echarts[\\/]/,
              priority: 30
            },
            {
              name: "vue-vendor",
              test: /node_modules[\\/](?:@vue|vue|vue-router|pinia)[\\/]/,
              priority: 20
            },
            {
              name: "vendor",
              // Keep Element Plus transitive form/positioning dependencies on their
              // lazy route boundaries instead of pulling them into the initial
              // Lucide-backed vendor chunk.
              test:
                /node_modules[\\/](?!(?:element-plus|@element-plus|@popperjs|async-validator|lodash(?:-es|-unified)?)[\\/])/,
              priority: 10
            }
          ]
        }
      }
    }
  }
});
