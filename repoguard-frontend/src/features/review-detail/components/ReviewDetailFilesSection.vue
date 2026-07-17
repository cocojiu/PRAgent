<template>
  <article v-loading="missingTestsLoading" class="dashboard-card">
    <div class="card-title-row">
      <h2>缺失测试</h2>
      <span class="count-badge">{{ missingTestsTotal }} 条</span>
    </div>
    <div v-if="archived && missingTestsTotal > 0" class="archive-section-note">
      历史缺失测试明细已归档，当前保留 {{ missingTestsTotal }} 条计数。
    </div>
    <div v-else-if="!missingTestsLoaded && missingTestsTotal > 0" class="lazy-section-actions">
      <el-button type="primary" plain :loading="missingTestsLoading" @click="$emit('missingTestsLoad')">
        <RefreshCw :size="16" />
        加载缺失测试
      </el-button>
    </div>
    <el-table
      v-else
      :data="missingTests"
      class="rg-table"
      size="large"
      aria-label="缺失测试列表"
    >
      <el-table-column prop="file" label="文件" min-width="320" />
      <el-table-column prop="method" label="涉及类/方法" min-width="220" />
      <el-table-column prop="type" label="缺失测试类型" width="160" />
      <el-table-column prop="suggestion" label="建议" min-width="280" />
      <template #empty>
        <el-empty description="暂无缺失测试建议" />
      </template>
    </el-table>
    <el-pagination
      v-if="missingTestsLoaded && missingTestsTotal > pageSize"
      class="detail-pagination"
      layout="prev, pager, next"
      :current-page="missingTestsPage"
      :page-size="pageSize"
      :total="missingTestsTotal"
      @current-change="$emit('missingTestsPageChange', $event)"
    />
  </article>

  <article v-loading="changedFilesLoading" class="dashboard-card">
    <div class="card-title-row">
      <h2>变更文件</h2>
      <span class="count-badge">{{ changedFilesTotal }} 个</span>
    </div>
    <div v-if="archived && changedFilesTotal > 0" class="archive-section-note">
      历史变更文件明细已归档，当前保留 {{ changedFilesTotal }} 个文件计数。
    </div>
    <div v-else-if="!changedFilesLoaded && changedFilesTotal > 0" class="lazy-section-actions">
      <el-button type="primary" plain :loading="changedFilesLoading" @click="$emit('changedFilesLoad')">
        <RefreshCw :size="16" />
        加载变更文件
      </el-button>
    </div>
    <el-table
      v-else
      :data="renderedChangedFiles"
      class="rg-table"
      size="large"
      aria-label="变更文件列表"
    >
      <el-table-column label="文件路径" min-width="420">
        <template #default="{ row }">
          <div class="changed-file-cell">
            <code>{{ row.path }}</code>
            <span v-if="row.findingCount" class="count-badge warning">{{ row.findingCount }} 条问题</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="变更类型" width="140">
        <template #default="{ row }">
          <span class="file-type">{{ changeTypeText(row.changeType) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="变更行数" width="160">
        <template #default="{ row }">
          <span class="additions">+{{ row.additions }}</span>
          <span class="deletions"> -{{ row.deletions }}</span>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="暂无变更文件" />
      </template>
    </el-table>
    <p v-if="changedFilesHiddenCount" class="render-budget-note">
      当前页另有 {{ changedFilesHiddenCount }} 个文件
    </p>
    <el-pagination
      v-if="changedFilesLoaded && changedFilesTotal > pageSize"
      class="detail-pagination"
      layout="prev, pager, next"
      :current-page="changedFilesPage"
      :page-size="pageSize"
      :total="changedFilesTotal"
      @current-change="$emit('changedFilesPageChange', $event)"
    />
  </article>
</template>

<script setup lang="ts">
import { computed, watch } from "vue";
import { RefreshCw } from "@lucide/vue";
import {
  boundedDetailItems,
  hiddenDetailItemCount,
  observeDetailRegionRender
} from "../reviewDetailRenderBudget";
import type { ChangedFile, MissingTest } from "@/types";

type ChangedFileWithFindingCount = ChangedFile & { findingCount: number };

const props = defineProps<{
  archived: boolean;
  missingTests: MissingTest[];
  changedFiles: ChangedFileWithFindingCount[];
  missingTestsLoaded: boolean;
  changedFilesLoaded: boolean;
  missingTestsLoading: boolean;
  changedFilesLoading: boolean;
  missingTestsPage: number;
  changedFilesPage: number;
  pageSize: number;
  missingTestsTotal: number;
  changedFilesTotal: number;
  changeTypeText: (changeType: ChangedFile["changeType"] | string) => string;
}>();

defineEmits<{
  missingTestsLoad: [];
  changedFilesLoad: [];
  missingTestsPageChange: [page: number];
  changedFilesPageChange: [page: number];
}>();

const renderedChangedFiles = computed(() => boundedDetailItems(props.changedFiles));
const changedFilesHiddenCount = computed(() => hiddenDetailItemCount(props.changedFiles));

watch(
  () => [props.changedFilesLoaded, props.changedFilesPage, props.changedFiles.length, props.changedFilesTotal] as const,
  ([loaded]) => {
    if (!loaded) {
      return;
    }
    void observeDetailRegionRender({
      region: "review-detail.changed-files",
      operation: "fetchReviewChangedFiles",
      itemCount: renderedChangedFiles.value.length,
      totalCount: props.changedFilesTotal,
      startedAtMs: now()
    });
  },
  { flush: "post" }
);

const now = () => (typeof performance === "undefined" ? Date.now() : performance.now());
</script>
