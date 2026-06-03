<template>
  <div class="detail-page">
    <div class="detail-header">
      <div>
        <button class="back-link" type="button" @click="goBack">
          <ArrowLeft :size="18" />
          返回审查任务
        </button>
        <div class="detail-title-row">
          <h1>PR #{{ selectedTask.prNumber }} - {{ selectedTask.title }}</h1>
          <span :class="`status-pill ${statusClass(selectedTask.status)}`">{{ statusText(selectedTask.status) }}</span>
          <span :class="`risk-pill ${selectedTask.riskLevel}`">{{ riskText(selectedTask.riskLevel) }}</span>
        </div>
        <p class="detail-meta">
          <Github :size="20" />
          {{ selectedTask.organization }} / {{ selectedTask.repository }}
          <span>创建时间：{{ selectedTask.createdAt }}</span>
        </p>
      </div>
      <div class="detail-actions">
        <el-button size="large">
          在 GitHub 查看
          <ExternalLink :size="16" />
        </el-button>
        <el-button type="primary" size="large">
          <RefreshCw :size="16" />
          重试
        </el-button>
      </div>
    </div>

    <section class="detail-kpi-grid">
      <div class="detail-kpi">
        <div class="metric-icon metric-icon--blue"><Archive :size="26" /></div>
        <div><p>仓库</p><strong>{{ selectedTask.repository }}</strong><span>{{ selectedTask.organization }}</span></div>
      </div>
      <div class="detail-kpi">
        <div class="metric-icon metric-icon--purple"><GitBranch :size="26" /></div>
        <div><p>Commit</p><strong>{{ selectedTask.commit }} <Copy :size="15" /></strong><span>{{ selectedTask.branch }}</span></div>
      </div>
      <div class="detail-kpi">
        <div class="metric-icon metric-icon--green"><Clock :size="26" /></div>
        <div><p>耗时</p><strong>{{ selectedTask.duration }}</strong><span>开始于 {{ reviewTimeline[0]?.time }}</span></div>
      </div>
      <div class="detail-kpi">
        <div class="metric-icon metric-icon--orange"><MessagesSquare :size="26" /></div>
        <div><p>MQ 重试</p><strong>{{ selectedTask.mqRetries }} 次</strong><span>{{ selectedTask.mqRetries ? "存在重试" : "首次入队成功" }}</span></div>
      </div>
    </section>

    <div class="detail-layout">
      <main class="detail-main">
        <article class="dashboard-card summary-card">
          <h2>审查摘要</h2>
          <p>本次审查发现的问题按风险等级汇总如下，详情可在问题列表中查看。</p>
          <div class="summary-stats">
            <div class="summary-stat high"><span>高风险</span><strong>{{ findingCounts.high }}</strong></div>
            <div class="summary-stat medium"><span>中风险</span><strong>{{ findingCounts.medium }}</strong></div>
            <div class="summary-stat low"><span>低风险</span><strong>{{ findingCounts.low }}</strong></div>
            <div class="summary-stat info"><span>提示</span><strong>{{ findingCounts.info }}</strong></div>
          </div>
        </article>

        <article class="dashboard-card">
          <h2>发现的问题</h2>
          <el-table :data="reviewFindings" class="rg-table" size="large" aria-label="审查发现的问题">
            <el-table-column label="严重级别" width="110">
              <template #default="{ row }">
                <span :class="`risk-pill ${row.severity}`">{{ riskText(row.severity) }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="file" label="文件" min-width="260" />
            <el-table-column prop="line" label="行号" width="80" />
            <el-table-column prop="message" label="问题说明" min-width="220" />
            <el-table-column prop="recommendation" label="修改建议" min-width="280" />
          </el-table>
        </article>

        <article class="dashboard-card">
          <h2>缺失测试</h2>
          <el-table :data="missingTests" class="rg-table" size="large" aria-label="缺失测试列表">
            <el-table-column prop="file" label="文件" min-width="320" />
            <el-table-column prop="method" label="涉及类/方法" min-width="220" />
            <el-table-column prop="type" label="缺失测试类型" width="160" />
            <el-table-column prop="suggestion" label="建议" min-width="280" />
          </el-table>
        </article>

        <article class="dashboard-card">
          <h2>变更文件</h2>
          <el-table :data="changedFiles" class="rg-table" size="large" aria-label="变更文件列表">
            <el-table-column prop="path" label="文件路径" min-width="420" />
            <el-table-column label="变更类型" width="140">
              <template #default="{ row }">
                <span class="file-type">{{ row.changeType }}</span>
              </template>
            </el-table-column>
            <el-table-column label="变更行数" width="160">
              <template #default="{ row }">
                <span class="additions">+{{ row.additions }}</span>
                <span class="deletions"> -{{ row.deletions }}</span>
              </template>
            </el-table-column>
          </el-table>
        </article>
      </main>

      <aside class="detail-side">
        <article class="dashboard-card">
          <h2>任务时间线</h2>
          <ol class="timeline">
            <li v-for="item in reviewTimeline" :key="item.label">
              <span></span>
              <b>{{ item.label }}</b>
              <em>{{ item.time }}</em>
            </li>
          </ol>
        </article>

        <article class="dashboard-card side-card">
          <h2>LLM 状态</h2>
          <dl>
            <dt>任务状态</dt><dd><span :class="`status-pill ${statusClass(selectedTask.llmStatus)}`">{{ statusText(selectedTask.llmStatus) }}</span></dd>
            <dt>耗时</dt><dd>{{ selectedTask.duration }}</dd>
            <dt>风险等级</dt><dd>{{ riskText(selectedTask.riskLevel) }}</dd>
          </dl>
        </article>

        <article class="dashboard-card side-card">
          <h2>RabbitMQ</h2>
          <dl>
            <dt>投递次数</dt><dd>{{ selectedTask.mqRetries + 1 }}</dd>
            <dt>重试次数</dt><dd>{{ selectedTask.mqRetries }}</dd>
            <dt>消费状态</dt><dd><span class="status-pill success">已确认</span></dd>
          </dl>
        </article>
      </aside>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from "vue";
import { Archive, ArrowLeft, Clock, Copy, ExternalLink, GitBranch, Github, MessagesSquare, RefreshCw } from "lucide-vue-next";
import { useRouter } from "vue-router";
import { changedFiles, missingTests, reviewFindings, reviewTimeline, selectedTask } from "@/mocks/reviewTasks";
import type { RiskLevel } from "@/types";
import { riskText } from "@/utils/risk";
import { statusClass, statusText } from "@/utils/status";

const router = useRouter();

const findingCounts = computed<Record<RiskLevel, number>>(() =>
  reviewFindings.reduce(
    (counts, finding) => {
      counts[finding.severity] += 1;
      return counts;
    },
    { critical: 0, high: 0, medium: 0, low: 0, info: 0 }
  )
);

const goBack = () => {
  router.push({ name: "tasks" });
};
</script>
