<template>
  <section class="bottom-grid">
    <article class="dashboard-card">
      <h2>近期高风险审查</h2>
      <el-table :data="highRiskReviews" class="rg-table" size="large" max-height="360" aria-label="近期高风险审查列表">
        <el-table-column prop="title" label="PR 标题" min-width="220" />
        <el-table-column prop="repository" label="仓库" width="150" />
        <el-table-column label="风险等级" width="110">
          <template #default="{ row }">
            <span :class="`risk-pill ${row.riskLevel}`">{{ riskText(row.riskLevel) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="ruleHits" label="规则命中" width="100" />
        <el-table-column prop="reviewedAt" label="审查时间" width="170" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <span class="status-pill success">{{ row.status }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default>
            <RouterLink class="table-link" :to="{ name: 'tasks' }">查看</RouterLink>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无高风险审查" />
        </template>
      </el-table>
      <RouterLink class="card-footer-link" :to="{ name: 'tasks' }">查看更多</RouterLink>
    </article>

    <article class="dashboard-card">
      <h2>高频失败规则</h2>
      <el-table :data="failedRules" class="rg-table" size="large" max-height="360" aria-label="高频失败规则列表">
        <el-table-column prop="name" label="规则名称" min-width="180" />
        <el-table-column prop="count" label="命中次数" width="100" />
        <el-table-column label="趋势" width="100">
          <template #default="{ row }">
            <span :class="row.direction === 'up' ? 'trend danger' : 'trend'">
              {{ row.direction === "up" ? "上升" : "下降" }} {{ row.trend }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="percent" label="占比" width="80" />
        <template #empty>
          <el-empty description="暂无失败规则统计" />
        </template>
      </el-table>
      <RouterLink class="card-footer-link" :to="{ name: 'rules' }">查看更多</RouterLink>
    </article>

    <article class="dashboard-card health-card">
      <h2>系统健康</h2>
      <div v-if="systemHealth.length" class="health-list">
        <div v-for="item in systemHealth" :key="item.name" class="health-item">
          <span>{{ item.name }}</span>
          <b>● {{ item.status }}</b>
        </div>
      </div>
      <el-empty v-else description="暂无健康检查数据" />
      <div class="health-footer">
        <span>最后检查：{{ lastHealthCheckAt }}</span>
        <button class="table-link" type="button" :disabled="loading" @click="$emit('refresh')">刷新</button>
      </div>
    </article>
  </section>
</template>

<script setup lang="ts">
import { RouterLink } from "vue-router";
import { riskText } from "@/utils/risk";
import type { FailedRuleStat, HighRiskReview, SystemHealthItem } from "@/types";

defineProps<{
  highRiskReviews: HighRiskReview[];
  failedRules: FailedRuleStat[];
  systemHealth: SystemHealthItem[];
  lastHealthCheckAt: string;
  loading: boolean;
}>();

defineEmits<{
  refresh: [];
}>();
</script>
