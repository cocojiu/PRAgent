<template>
  <div class="filter-bar">
    <el-select v-model="repositoryModel" placeholder="全部仓库" clearable>
      <el-option label="全部仓库" value="" />
      <el-option v-for="repo in repositories" :key="repo" :label="repo" :value="repo" />
    </el-select>
    <el-select v-model="statusModel" placeholder="全部状态" clearable>
      <el-option label="全部状态" value="" />
      <el-option label="已完成" value="completed" />
      <el-option label="审查中" value="reviewing" />
      <el-option label="失败" value="failed" />
    </el-select>
    <el-select v-model="riskModel" placeholder="全部风险等级" clearable>
      <el-option label="全部风险等级" value="" />
      <el-option label="高风险" value="high" />
      <el-option label="中风险" value="medium" />
      <el-option label="低风险" value="low" />
    </el-select>
    <el-select v-model="sourceModel" placeholder="全部来源" clearable>
      <el-option label="全部来源" value="" />
      <el-option label="手动输入" value="manual_input" />
      <el-option label="PR 选择" value="github_pr_picker" />
      <el-option label="GitHub 自动触发" value="github_webhook" />
      <el-option label="复用已有" value="existing_reused" />
    </el-select>
    <el-input v-model="keywordModel" class="search-input" placeholder="搜索 PR 标题、作者或 Commit ID" clearable>
      <template #suffix><Search :size="18" /></template>
    </el-input>
    <el-button type="primary" plain :loading="loading" @click="emit('refresh')">
      <RefreshCw :size="16" />
      刷新
    </el-button>
  </div>
</template>

<script setup lang="ts">
import { computed } from "vue";
import { RefreshCw, Search } from "lucide-vue-next";
import type { ReviewStatus, ReviewTaskTriggerSource, RiskLevel } from "@/types";

const props = defineProps<{
  keyword: string;
  loading: boolean;
  repositories: string[];
  repository: string;
  risk: RiskLevel | "";
  source: ReviewTaskTriggerSource | "";
  status: ReviewStatus | "";
}>();

const emit = defineEmits<{
  refresh: [];
  "update:keyword": [value: string];
  "update:repository": [value: string];
  "update:risk": [value: RiskLevel | ""];
  "update:source": [value: ReviewTaskTriggerSource | ""];
  "update:status": [value: ReviewStatus | ""];
}>();

const repositoryModel = computed({
  get: () => props.repository,
  set: (value: string) => emit("update:repository", value)
});

const statusModel = computed({
  get: () => props.status,
  set: (value: ReviewStatus | "") => emit("update:status", value)
});

const riskModel = computed({
  get: () => props.risk,
  set: (value: RiskLevel | "") => emit("update:risk", value)
});

const sourceModel = computed({
  get: () => props.source,
  set: (value: ReviewTaskTriggerSource | "") => emit("update:source", value)
});

const keywordModel = computed({
  get: () => props.keyword,
  set: (value: string) => emit("update:keyword", value)
});
</script>
