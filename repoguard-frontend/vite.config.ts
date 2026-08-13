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
  initialRequests: 16,
  maxAsyncJavaScriptGzip: 140 * KIB,
  overviewRouteIncrementalJavaScriptGzip: 60 * KIB,
  overviewRouteIncrementalCssGzip: 12 * KIB,
  overviewRouteIncrementalRequests: 16
} as const;

const apiProxy = {
  "/api": {
    target: "http://localhost:8081",
    changeOrigin: true
  }
};

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
    const initialRequests = initialChunkNames.size + initialCssNames.size;

    const isOverviewModule = (moduleId: string | null | undefined) =>
      moduleId?.replaceAll("\\", "/").endsWith("/src/pages/OverviewPage.vue") === true;
    const overviewRouteChunk = chunks.find((chunk) => isOverviewModule(chunk.facadeModuleId))
      ?? chunks.find((chunk) => chunk.moduleIds.some(isOverviewModule));
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
    const overviewRouteIncrementalJavaScriptGzip = [...overviewRouteIncrementalChunkNames].reduce(
      (total, fileName) => total + gzipSize(chunkByFileName.get(fileName)?.code ?? ""),
      0
    );
    const overviewRouteIncrementalCssGzip = [...overviewRouteIncrementalCssNames].reduce(
      (total, fileName) => {
        const asset = bundle[fileName];
        return total + (asset?.type === "asset" ? gzipSize(asset.source) : 0);
      },
      0
    );
    const overviewRouteIncrementalRequests =
      overviewRouteIncrementalChunkNames.size + overviewRouteIncrementalCssNames.size;

    const formatJavaScriptContributors = (fileNames: Iterable<string>) =>
      [...fileNames]
        .map((fileName) => ({
          fileName,
          gzipBytes: gzipSize(chunkByFileName.get(fileName)?.code ?? "")
        }))
        .sort((left, right) => right.gzipBytes - left.gzipBytes)
        .map(({ fileName, gzipBytes }) => `${fileName} (${formatKiB(gzipBytes)})`)
        .join(", ");
    const formatCssContributors = (fileNames: Iterable<string>) =>
      [...fileNames]
        .map((fileName) => {
          const asset = bundle[fileName];
          return {
            fileName,
            gzipBytes: asset?.type === "asset" ? gzipSize(asset.source) : 0
          };
        })
        .sort((left, right) => right.gzipBytes - left.gzipBytes)
        .map(({ fileName, gzipBytes }) => `${fileName} (${formatKiB(gzipBytes)})`)
        .join(", ");
    const formatRequestContributors = (
      chunkNames: Iterable<string>,
      cssNames: Iterable<string>
    ) => [...chunkNames, ...cssNames].join(", ");
    const formatModuleContributors = (moduleIds: string[]) =>
      moduleIds
        .map((moduleId) => moduleId.replaceAll("\\", "/"))
        .filter((moduleId) => moduleId.includes("/src/") || moduleId.includes("/node_modules/"))
        .slice(0, 12)
        .join(", ");

    const asyncJavaScriptChunks = chunks
      .filter((chunk) => !initialChunkNames.has(chunk.fileName))
      .map((chunk) => ({ fileName: chunk.fileName, gzipBytes: gzipSize(chunk.code) }))
      .sort((left, right) => right.gzipBytes - left.gzipBytes);
    const largestAsyncChunk = asyncJavaScriptChunks[0] ?? { fileName: "none", gzipBytes: 0 };

    const measurements = [
      {
        label: "initial shared JavaScript gzip",
        actual: initialJavaScriptGzip,
        budget: bundleBudgets.initialJavaScriptGzip,
        format: formatKiB,
        contributors: formatJavaScriptContributors(initialChunkNames)
      },
      {
        label: "initial shared CSS gzip",
        actual: initialCssGzip,
        budget: bundleBudgets.initialCssGzip,
        format: formatKiB,
        contributors: formatCssContributors(initialCssNames)
      },
      {
        label: "initial shared requests",
        actual: initialRequests,
        budget: bundleBudgets.initialRequests,
        format: String,
        contributors: formatRequestContributors(initialChunkNames, initialCssNames)
      },
      {
        label: `largest async JavaScript gzip (${largestAsyncChunk.fileName})`,
        actual: largestAsyncChunk.gzipBytes,
        budget: bundleBudgets.maxAsyncJavaScriptGzip,
        format: formatKiB,
        contributors: formatModuleContributors(
          chunkByFileName.get(largestAsyncChunk.fileName)?.moduleIds ?? []
        )
      },
      {
        label: "overview route incremental JavaScript gzip",
        actual: overviewRouteIncrementalJavaScriptGzip,
        budget: bundleBudgets.overviewRouteIncrementalJavaScriptGzip,
        format: formatKiB,
        contributors: formatJavaScriptContributors(overviewRouteIncrementalChunkNames)
      },
      {
        label: "overview route incremental CSS gzip",
        actual: overviewRouteIncrementalCssGzip,
        budget: bundleBudgets.overviewRouteIncrementalCssGzip,
        format: formatKiB,
        contributors: formatCssContributors(overviewRouteIncrementalCssNames)
      },
      {
        label: "overview route incremental requests",
        actual: overviewRouteIncrementalRequests,
        budget: bundleBudgets.overviewRouteIncrementalRequests,
        format: String,
        contributors: formatRequestContributors(
          overviewRouteIncrementalChunkNames,
          overviewRouteIncrementalCssNames
        )
      }
    ];
    const summary = measurements
      .map(({ label, actual, budget, format }) => `${label}: ${format(actual)} / ${format(budget)}`)
      .join("; ");
    const violations = measurements.filter(({ actual, budget }) => actual > budget);

    if (violations.length > 0) {
      const violationDetails = violations
        .map(({ label, contributors }) => `${label} contributors: ${contributors || "none"}`)
        .join("; ");
      this.error(`[bundle-budget] Budget exceeded. ${summary}. ${violationDetails}`);
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
    proxy: apiProxy
  },
  preview: {
    proxy: apiProxy
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
