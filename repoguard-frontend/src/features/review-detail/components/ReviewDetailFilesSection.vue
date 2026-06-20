<template>
  <article class="dashboard-card">
    <h2>缺失测试</h2>
    <el-table :data="missingTests" class="rg-table" size="large" aria-label="缺失测试列表">
      <el-table-column prop="file" label="文件" min-width="320" />
      <el-table-column prop="method" label="涉及类/方法" min-width="220" />
      <el-table-column prop="type" label="缺失测试类型" width="160" />
      <el-table-column prop="suggestion" label="建议" min-width="280" />
      <template #empty>
        <el-empty description="暂无缺失测试建议" />
      </template>
    </el-table>
  </article>

  <article class="dashboard-card">
    <h2>变更文件</h2>
    <el-table :data="changedFiles" class="rg-table" size="large" aria-label="变更文件列表">
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
  </article>
</template>

<script setup lang="ts">
import type { ChangedFile, MissingTest } from "@/types";

type ChangedFileWithFindingCount = ChangedFile & { findingCount: number };

defineProps<{
  missingTests: MissingTest[];
  changedFiles: ChangedFileWithFindingCount[];
  changeTypeText: (changeType: ChangedFile["changeType"] | string) => string;
}>();
</script>
