<template>
  <div v-loading="loading" class="detail-page">
    <el-alert v-if="errorMessage" class="page-alert" type="error" :title="errorMessage" show-icon :closable="false" />

    <template v-if="selectedTask">
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
        <el-button size="large" @click="openPrUrl">
          在 GitHub 查看
          <ExternalLink :size="16" />
        </el-button>
        <el-tooltip content="执行链路接口尚未接入">
          <span>
            <el-button type="primary" size="large" disabled>
              <RefreshCw :size="16" />
              重试
            </el-button>
          </span>
        </el-tooltip>
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
            <template #empty>
              <el-empty description="暂无审查问题" />
            </template>
          </el-table>
        </article>

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
            <template #empty>
              <el-empty description="暂无变更文件" />
            </template>
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
            <dt>任务状态</dt><dd><span :class="`status-pill ${statusClass(selectedTask.llm.status)}`">{{ statusText(selectedTask.llm.status) }}</span></dd>
            <dt>耗时</dt><dd>{{ selectedTask.llm.duration }}</dd>
            <dt>风险等级</dt><dd>{{ riskText(selectedTask.llm.riskLevel) }}</dd>
          </dl>
        </article>

        <article class="dashboard-card side-card">
          <h2>RabbitMQ</h2>
          <dl>
            <dt>投递次数</dt><dd>{{ selectedTask.rabbitMq.deliveryCount }}</dd>
            <dt>重试次数</dt><dd>{{ selectedTask.rabbitMq.retryCount }}</dd>
            <dt>消费状态</dt><dd><span class="status-pill success">{{ selectedTask.rabbitMq.consumeStatus }}</span></dd>
          </dl>
        </article>
      </aside>
    </div>
    </template>
    <el-empty v-else-if="!loading" :description="emptyDescription">
      <el-button type="primary" plain @click="goBack">返回列表</el-button>
      <el-button :loading="loading" @click="loadDetail">重新加载</el-button>
    </el-empty>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { ElMessage } from "element-plus";
import { Archive, ArrowLeft, Clock, Copy, ExternalLink, GitBranch, Github, MessagesSquare, RefreshCw } from "lucide-vue-next";
import { useRoute, useRouter } from "vue-router";
import { fetchReviewDetail } from "@/api/reviews";
import type { ReviewTaskDetail, RiskLevel } from "@/types";
import { riskText } from "@/utils/risk";
import { statusClass, statusText } from "@/utils/status";

const router = useRouter();
const route = useRoute();
const loading = ref(false);
const errorMessage = ref("");
const selectedTask = ref<ReviewTaskDetail | null>(null);

const reviewFindings = computed(() => selectedTask.value?.findings ?? []);
const missingTests = computed(() => selectedTask.value?.missingTests ?? []);
const changedFiles = computed(() => selectedTask.value?.changedFiles ?? []);
const reviewTimeline = computed(() => selectedTask.value?.timeline ?? []);
const emptyDescription = computed(() => (errorMessage.value ? "审查详情加载失败" : "未找到审查任务"));

const findingCounts = computed<Record<RiskLevel, number>>(() =>
  reviewFindings.value.reduce(
    (counts, finding) => {
      counts[finding.severity] += 1;
      return counts;
    },
    { critical: 0, high: 0, medium: 0, low: 0, info: 0 }
  )
);

const loadDetail = async () => {
  const id = Number(route.params.id);
  if (!Number.isFinite(id)) {
    ElMessage.error("审查任务 ID 无效");
    return;
  }

  loading.value = true;
  errorMessage.value = "";
  try {
    selectedTask.value = await fetchReviewDetail(id);
  } catch (error) {
    selectedTask.value = null;
    errorMessage.value = error instanceof Error ? error.message : "审查详情加载失败";
    ElMessage.error(errorMessage.value);
  } finally {
    loading.value = false;
  }
};

const goBack = () => {
  router.push({ name: "tasks" });
};

const openPrUrl = () => {
  if (selectedTask.value?.prUrl) {
    window.open(selectedTask.value.prUrl, "_blank", "noopener,noreferrer");
  }
};

onMounted(loadDetail);
</script>
