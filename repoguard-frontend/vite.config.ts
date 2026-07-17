import { fileURLToPath, URL } from "node:url";
import { gzipSync } from "node:zlib";
import { defineConfig, type Plugin } from "vite";
import vue from "@vitejs/plugin-vue";

const KIB = 1024;

const bundleBudgets = {
  initialJavaScriptGzip: 190 * KIB,
  initialCssGzip: 32 * KIB,
  maxAsyncJavaScriptGzip: 140 * KIB
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

    const collectInitialChunk = (fileName: string) => {
      if (initialChunkNames.has(fileName)) {
        return;
      }

      const chunk = chunkByFileName.get(fileName);
      if (!chunk) {
        return;
      }

      initialChunkNames.add(fileName);
      chunk.imports.forEach(collectInitialChunk);
    };

    chunks.filter((chunk) => chunk.isEntry).forEach((chunk) => collectInitialChunk(chunk.fileName));

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

    const asyncJavaScriptChunks = chunks
      .filter((chunk) => !initialChunkNames.has(chunk.fileName))
      .map((chunk) => ({ fileName: chunk.fileName, gzipBytes: gzipSize(chunk.code) }))
      .sort((left, right) => right.gzipBytes - left.gzipBytes);
    const largestAsyncChunk = asyncJavaScriptChunks[0] ?? { fileName: "none", gzipBytes: 0 };

    const measurements = [
      {
        label: "initial JavaScript gzip",
        actual: initialJavaScriptGzip,
        budget: bundleBudgets.initialJavaScriptGzip
      },
      {
        label: "initial CSS gzip",
        actual: initialCssGzip,
        budget: bundleBudgets.initialCssGzip
      },
      {
        label: `largest async JavaScript gzip (${largestAsyncChunk.fileName})`,
        actual: largestAsyncChunk.gzipBytes,
        budget: bundleBudgets.maxAsyncJavaScriptGzip
      }
    ];
    const summary = measurements
      .map(({ label, actual, budget }) => `${label}: ${formatKiB(actual)} / ${formatKiB(budget)}`)
      .join("; ");
    const violations = measurements.filter(({ actual, budget }) => actual > budget);

    if (violations.length > 0) {
      this.error(`[bundle-budget] Budget exceeded. ${summary}`);
    }

    this.info(`[bundle-budget] ${summary}`);
  }
});

export default defineConfig({
  plugins: [vue(), bundleBudgetPlugin()],
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
              maxSize: 350 * KIB,
              priority: 30
            },
            {
              name: "element-plus",
              test: /node_modules[\\/]element-plus[\\/]/,
              priority: 20
            },
            {
              name: "vue-vendor",
              test: /node_modules[\\/](?:@vue|vue|vue-router|pinia)[\\/]/,
              priority: 20
            },
            {
              name: "vendor",
              test: /node_modules[\\/]/,
              priority: 10
            }
          ]
        }
      }
    }
  }
});
