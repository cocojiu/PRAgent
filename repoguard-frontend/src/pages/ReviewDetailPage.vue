<template>
  <div class="detail-page">
    <div class="detail-header">
      <div>
        <div class="detail-title-row">
          <h1>PR #{{ selectedTask.prNumber }} - {{ selectedTask.title }}</h1>
          <span class="status-pill success">已完成</span>
          <span class="risk-pill high">高风险</span>
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
        <div><p>耗时</p><strong>{{ selectedTask.duration }}</strong><span>开始于 14:32:22</span></div>
      </div>
      <div class="detail-kpi">
        <div class="metric-icon metric-icon--orange"><MessagesSquare :size="26" /></div>
        <div><p>MQ 重试</p><strong>1 次</strong><span>首次入队成功</span></div>
      </div>
    </section>

    <div class="detail-layout">
      <main class="detail-main">
        <article class="dashboard-card summary-card">
          <h2>审查摘要</h2>
          <p>本次审查共发现 7 个问题，其中高风险 2 个，中风险 3 个，低风险 2 个。主要问题集中在敏感信息硬编码、缺少单元测试以及不规范的日志打印。</p>
          <div class="summary-stats">
            <div class="summary-stat high"><span>高风险</span><strong>2</strong></div>
            <div class="summary-stat medium"><span>中风险</span><strong>3</strong></div>
            <div class="summary-stat low"><span>低风险</span><strong>2</strong></div>
            <div class="summary-stat info"><span>提示</span><strong>0</strong></div>
          </div>
        </article>

        <article class="dashboard-card">
          <h2>发现的问题</h2>
          <el-table :data="reviewFindings" class="rg-table" size="large">
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
          <div class="card-footer-link">查看更多 ›</div>
        </article>

        <article class="dashboard-card">
          <h2>缺失测试</h2>
          <el-table :data="missingTests" class="rg-table" size="large">
            <el-table-column prop="file" label="文件" min-width="320" />
            <el-table-column prop="method" label="涉及类/方法" min-width="220" />
            <el-table-column prop="type" label="缺失的测试类型" width="160" />
            <el-table-column prop="suggestion" label="建议" min-width="280" />
          </el-table>
        </article>

        <article class="dashboard-card">
          <h2>变更文件</h2>
          <el-table :data="changedFiles" class="rg-table" size="large">
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
            <dt>模型提供商</dt><dd>阿里云百炼（DashScope）</dd>
            <dt>模型名称</dt><dd>qwen-plus</dd>
            <dt>请求 Token</dt><dd>2,342</dd>
            <dt>响应 Token</dt><dd>1,876</dd>
            <dt>耗时</dt><dd>46.32 秒</dd>
            <dt>状态</dt><dd><span class="status-pill success">成功</span></dd>
          </dl>
          <a class="table-link">查看 LLM 输出详情 ›</a>
        </article>

        <article class="dashboard-card side-card">
          <h2>RabbitMQ</h2>
          <dl>
            <dt>交换机</dt><dd>repoguard.review.exchange</dd>
            <dt>队列</dt><dd>repoguard.review.queue</dd>
            <dt>消息 ID</dt><dd>8f2c7d9e-0a6b-4d1a</dd>
            <dt>投递次数</dt><dd>1</dd>
            <dt>消费状态</dt><dd><span class="status-pill success">已确认</span></dd>
          </dl>
        </article>

        <article class="dashboard-card side-card">
          <h2>GitHub 评论</h2>
          <dl>
            <dt>评论 ID</dt><dd>2233445566</dd>
            <dt>创建时间</dt><dd>2025-05-31 14:35:09</dd>
            <dt>状态</dt><dd><span class="status-pill success">已发布</span></dd>
          </dl>
          <a class="table-link">在 GitHub 查看评论 ›</a>
        </article>
      </aside>
    </div>
  </div>
</template>

<script setup lang="ts">
import { Archive, Clock, Copy, ExternalLink, GitBranch, Github, MessagesSquare, RefreshCw } from "lucide-vue-next";
import { changedFiles, missingTests, reviewFindings, reviewTimeline, selectedTask } from "@/mocks/reviewTasks";
import type { RiskLevel } from "@/types";

const riskText = (risk: RiskLevel) => ({ high: "高风险", medium: "中风险", low: "低风险", critical: "严重", info: "提示" })[risk];
</script>
