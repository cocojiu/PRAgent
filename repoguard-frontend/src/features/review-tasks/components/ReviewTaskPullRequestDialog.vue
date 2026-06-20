<template>
  <el-dialog v-model="visibleModel" title="选择 GitHub PR" width="760px" append-to-body destroy-on-close>
    <el-alert
      v-if="error"
      class="page-alert"
      type="warning"
      :title="error"
      show-icon
      :closable="false"
    />
    <div v-else class="pr-picker-meta">
      <Github :size="18" />
      <span>{{ repositoryText }}</span>
      <span v-if="pullRequestsLoaded" class="pr-picker-cache">已预加载 {{ pullRequests.length }} 个 open PR</span>
      <el-button size="small" text :loading="loadingPullRequests" @click="emit('reload')">
        <RefreshCw :size="14" />
        刷新 PR
      </el-button>
    </div>
    <el-table
      v-loading="loadingPullRequests"
      :data="pullRequests"
      class="rg-table"
      size="large"
      highlight-current-row
      aria-label="GitHub PR 列表"
      @current-change="emit('select', $event)"
    >
      <el-table-column width="56">
        <template #default="{ row }">
          <el-radio v-model="selectedPullRequestNumberModel" :value="row.number" />
        </template>
      </el-table-column>
      <el-table-column label="PR" min-width="340">
        <template #default="{ row }">
          <div class="pr-option-cell">
            <strong>#{{ row.number }} {{ row.title }}</strong>
            <span>{{ row.author || "-" }} · {{ row.updatedAt || "-" }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="分支" min-width="160">
        <template #default="{ row }">
          <code>{{ row.branch || "-" }}</code>
        </template>
      </el-table-column>
      <el-table-column label="Commit" width="130">
        <template #default="{ row }">
          <code>{{ shortCommit(resolvePullRequestHeadSha(row)) }}</code>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="当前配置仓库暂无 open PR" />
      </template>
    </el-table>
    <template #footer>
      <el-button @click="visibleModel = false">取消</el-button>
      <el-button type="primary" :loading="creatingTask" :disabled="!canCreate" @click="emit('create')">
        创建审查任务
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed } from "vue";
import { Github, RefreshCw } from "lucide-vue-next";
import type { GithubPullRequestOption } from "@/types";

const props = defineProps<{
  canCreate: boolean;
  creatingTask: boolean;
  error: string;
  loadingPullRequests: boolean;
  pullRequests: GithubPullRequestOption[];
  pullRequestsLoaded: boolean;
  repositoryText: string;
  selectedPullRequestNumber?: number;
  visible: boolean;
}>();

const emit = defineEmits<{
  create: [];
  reload: [];
  select: [pullRequest?: GithubPullRequestOption];
  "update:selectedPullRequestNumber": [value?: number];
  "update:visible": [value: boolean];
}>();

const visibleModel = computed({
  get: () => props.visible,
  set: (value: boolean) => emit("update:visible", value)
});

const selectedPullRequestNumberModel = computed({
  get: () => props.selectedPullRequestNumber,
  set: (value?: number) => emit("update:selectedPullRequestNumber", value)
});

const resolvePullRequestHeadSha = (pullRequest: GithubPullRequestOption) => pullRequest.headSha || pullRequest.commit;

const shortCommit = (commit?: string) => (commit ? commit.slice(0, 7) : "-");
</script>
