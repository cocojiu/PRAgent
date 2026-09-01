<template>
  <section
    v-if="visible"
    class="personal-onboarding-card dashboard-card"
    aria-labelledby="personal-onboarding-title"
  >
    <div class="onboarding-header">
      <div>
        <span class="onboarding-eyebrow">个人模式 · 首次审查</span>
        <h2 id="personal-onboarding-title">10 分钟完成第一次 PR 审查</h2>
        <p>只配置 GitHub、LLM，然后预览一个 PR。MySQL 与 RabbitMQ 只做只读健康检查。</p>
      </div>
      <el-button text :loading="loading" @click="loadConfig">
        <RefreshCw :size="16" />
        重新检查
      </el-button>
    </div>

    <el-alert
      v-if="errorMessage"
      class="onboarding-alert"
      type="warning"
      :title="errorMessage"
      show-icon
      :closable="false"
    />

    <div v-if="loading && !githubConfig && !reviewPolicyConfig" class="onboarding-loading">
      正在读取个人模式必需配置...
    </div>

    <ol v-else class="onboarding-steps">
      <li
        v-for="step in steps"
        :key="step.id"
        :class="['onboarding-step', `onboarding-step--${step.state}`]"
      >
        <span class="onboarding-step-marker" aria-hidden="true">
          <LoaderCircle v-if="step.id === 'connections' && checking" class="onboarding-spinner" :size="18" />
          <CheckCircle2 v-else-if="step.state === 'done'" :size="18" />
          <CircleAlert v-else-if="step.id === 'connections' && connectionFailed" :size="18" />
          <span v-else>{{ stepIndex(step.id) }}</span>
        </span>
        <div class="onboarding-step-content">
          <div class="onboarding-step-title-row">
            <h3>{{ step.title }}</h3>
            <span class="onboarding-step-state">{{ stepStateText(step.state) }}</span>
          </div>
          <p>{{ step.description }}</p>
          <small v-if="step.id === 'connections' && connectionFailed" class="onboarding-step-error">
            {{ connectionErrorMessage || "请打开集成设置检查凭据、模型和默认仓库。" }}
          </small>
        </div>
        <el-button
          v-if="step.actionLabel"
          size="small"
          :type="step.id === 'preview' ? 'primary' : 'default'"
          :plain="step.id !== 'preview'"
          :disabled="!step.actionEnabled || (step.id === 'connections' && checking)"
          :loading="step.id === 'connections' && checking"
          @click="handleStepAction(step.id)"
        >
          {{ step.actionLabel }}
          <ArrowRight :size="15" />
        </el-button>
      </li>
    </ol>

    <p class="onboarding-safety-note">
      <PlugZap :size="16" />
      首次审查只生成结果和评论预览；只有在任务详情页明确确认后，才会向 GitHub 发布评论。
    </p>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { ArrowRight, CheckCircle2, CircleAlert, LoaderCircle, PlugZap, RefreshCw } from "@lucide/vue";
import {
  fetchGithubIntegrationConfig,
  fetchReviewPolicyConfig,
  testGithubIntegrationConnection,
  testReviewPolicyConnection
} from "@/api/config";
import { fetchGithubPullRequestOptions } from "@/api/reviews";
import type { ConnectionTestResult, GithubIntegrationConfig, ReviewPolicyConfig } from "@/types";
import { routeNames } from "@/router/names";
import { getErrorMessage } from "@/utils/errors";
import {
  buildPersonalOnboardingSteps,
  isGithubSetupComplete,
  isLlmSetupComplete,
  type PersonalOnboardingStepId,
  type PersonalOnboardingStepState
} from "../personalOnboarding";

const PREVIEW_LAUNCHED_KEY = "repoguard-personal-onboarding-preview-launched-v1";

type CheckStatus = "idle" | "running" | "success" | "failed";

const router = useRouter();
const loading = ref(false);
const checking = ref(false);
const errorMessage = ref("");
const connectionErrorMessage = ref("");
const githubConfig = ref<GithubIntegrationConfig>();
const reviewPolicyConfig = ref<ReviewPolicyConfig>();
const githubCheckStatus = ref<CheckStatus>("idle");
const llmCheckStatus = ref<CheckStatus>("idle");
const repositoryCheckStatus = ref<CheckStatus>("idle");
const pullRequestCount = ref<number>();
const previewLaunched = ref(false);

const githubConfigured = computed(() => isGithubSetupComplete(githubConfig.value));
const llmConfigured = computed(() => isLlmSetupComplete(reviewPolicyConfig.value));
const repositoryChecked = computed(() => repositoryCheckStatus.value === "success");
const connectionFailed = computed(() => [
  githubCheckStatus.value,
  llmCheckStatus.value,
  repositoryCheckStatus.value
].includes("failed"));
const visible = computed(() => !(previewLaunched.value && githubConfigured.value && llmConfigured.value));
const steps = computed(() => buildPersonalOnboardingSteps({
  githubConfigured: githubConfigured.value,
  llmConfigured: llmConfigured.value,
  githubConnectionVerified: githubCheckStatus.value === "success",
  llmConnectionVerified: llmCheckStatus.value === "success",
  repositoryChecked: repositoryChecked.value,
  pullRequestCount: pullRequestCount.value,
  previewLaunched: previewLaunched.value
}));

const readPreviewState = () => {
  try {
    previewLaunched.value = window.localStorage.getItem(PREVIEW_LAUNCHED_KEY) === "true";
  } catch {
    previewLaunched.value = false;
  }
};

const persistPreviewState = () => {
  try {
    window.localStorage.setItem(PREVIEW_LAUNCHED_KEY, "true");
  } catch {
    // A private browsing context may deny localStorage; the current session still works.
  }
};

const resetChecks = () => {
  githubCheckStatus.value = "idle";
  llmCheckStatus.value = "idle";
  repositoryCheckStatus.value = "idle";
  pullRequestCount.value = undefined;
  connectionErrorMessage.value = "";
};

const applyCheckResult = (
  result: PromiseSettledResult<ConnectionTestResult>,
  target: { value: CheckStatus },
  fallback: string
) => {
  if (result.status === "fulfilled") {
    target.value = result.value.success ? "success" : "failed";
    if (!result.value.success && !connectionErrorMessage.value) {
      connectionErrorMessage.value = result.value.message || fallback;
    }
    return result.value.success;
  }
  target.value = "failed";
  if (!connectionErrorMessage.value) {
    connectionErrorMessage.value = getErrorMessage(result.reason, fallback);
  }
  return false;
};

const checkConnections = async () => {
  if (checking.value || !githubConfigured.value || !llmConfigured.value) {
    return;
  }
  checking.value = true;
  connectionErrorMessage.value = "";
  githubCheckStatus.value = "running";
  llmCheckStatus.value = "running";
  repositoryCheckStatus.value = "idle";
  pullRequestCount.value = undefined;
  try {
    const [githubResult, llmResult] = await Promise.allSettled([
      testGithubIntegrationConnection(),
      testReviewPolicyConnection()
    ]);
    const githubHealthy = applyCheckResult(
      githubResult,
      githubCheckStatus,
      "GitHub 权限验证失败，请检查 Token 和默认仓库。"
    );
    applyCheckResult(
      llmResult,
      llmCheckStatus,
      "LLM 模型验证失败，请检查 provider、模型和 API Key。"
    );
    if (!githubHealthy) {
      repositoryCheckStatus.value = "failed";
      return;
    }
    try {
      const pullRequests = await fetchGithubPullRequestOptions();
      repositoryCheckStatus.value = "success";
      pullRequestCount.value = pullRequests.items.length;
      if (pullRequests.items.length === 0 && !connectionErrorMessage.value) {
        connectionErrorMessage.value = "GitHub 连接正常，但默认仓库暂无 open PR；创建一个 PR 后即可预览。";
      }
    } catch (error) {
      repositoryCheckStatus.value = "failed";
      connectionErrorMessage.value = getErrorMessage(
        error,
        "默认仓库检查失败，请确认仓库名称和 Token 的读取权限。"
      );
    }
  } finally {
    checking.value = false;
  }
};

const loadConfig = async () => {
  if (loading.value) {
    return;
  }
  loading.value = true;
  errorMessage.value = "";
  resetChecks();
  try {
    const [githubResult, llmResult] = await Promise.allSettled([
      fetchGithubIntegrationConfig(),
      fetchReviewPolicyConfig()
    ]);
    const failed: string[] = [];
    if (githubResult.status === "fulfilled") {
      githubConfig.value = githubResult.value;
    } else {
      failed.push("GitHub");
    }
    if (llmResult.status === "fulfilled") {
      reviewPolicyConfig.value = llmResult.value;
    } else {
      failed.push("LLM");
    }
    if (failed.length) {
      errorMessage.value = `${failed.join("、")} 配置读取失败，请点击“重新检查”；若仍失败，请确认后端已启动并使用管理员账号。`;
    }
  } finally {
    loading.value = false;
  }
};

const launchPreview = async () => {
  try {
    await router.push({ name: routeNames.tasks, query: { onboarding: "preview" } });
    previewLaunched.value = true;
    persistPreviewState();
  } catch (error) {
    errorMessage.value = getErrorMessage(error, "预览入口打开失败，请进入审查任务页面重试。");
  }
};

const handleStepAction = async (stepId: PersonalOnboardingStepId) => {
  if (stepId === "github" || stepId === "llm") {
    await router.push({ name: routeNames.integrations, query: { focus: stepId === "github" ? "github" : "spring-ai" } });
    return;
  }
  if (stepId === "connections") {
    await checkConnections();
    return;
  }
  await launchPreview();
};

const stepIndex = (stepId: PersonalOnboardingStepId) =>
  ["github", "llm", "connections", "preview"].indexOf(stepId) + 1;

const stepStateText = (state: PersonalOnboardingStepState) => {
  switch (state) {
    case "done":
      return "已完成";
    case "current":
      return "当前步骤";
    default:
      return "待完成";
  }
};

onMounted(() => {
  readPreviewState();
  void loadConfig();
});
</script>

<style scoped>
.personal-onboarding-card {
  margin-bottom: 24px;
  padding: 24px 26px 20px;
  border: 1px solid #dbeafe;
  background: linear-gradient(135deg, #ffffff 0%, #f5f9ff 100%);
}

.onboarding-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
}

.onboarding-eyebrow {
  color: #2563eb;
  font-size: 12px;
  font-weight: 800;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.onboarding-header h2 {
  margin: 8px 0 6px;
  color: #0f172a;
  font-size: 21px;
}

.onboarding-header p {
  margin: 0;
  color: #64748b;
  font-size: 13px;
  line-height: 1.6;
}

.onboarding-header :deep(.el-button) {
  flex: 0 0 auto;
}

.onboarding-header :deep(.el-button svg) {
  margin-right: 4px;
}

.onboarding-alert {
  margin-top: 18px;
}

.onboarding-loading {
  margin-top: 20px;
  padding: 16px;
  border-radius: 8px;
  color: #64748b;
  background: #eff6ff;
  font-size: 13px;
}

.onboarding-steps {
  display: grid;
  gap: 10px;
  margin: 20px 0 16px;
  padding: 0;
  list-style: none;
}

.onboarding-step {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 70px;
  padding: 12px 14px;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.82);
}

.onboarding-step--current {
  border-color: #93c5fd;
  box-shadow: 0 4px 14px rgba(37, 99, 235, 0.08);
}

.onboarding-step--done {
  border-color: #bbf7d0;
  background: #f0fdf4;
}

.onboarding-step-marker {
  display: grid;
  flex: 0 0 auto;
  place-items: center;
  width: 30px;
  height: 30px;
  border-radius: 50%;
  color: #64748b;
  background: #f1f5f9;
  font-size: 13px;
  font-weight: 800;
}

.onboarding-step--current .onboarding-step-marker {
  color: #1d4ed8;
  background: #dbeafe;
}

.onboarding-step--done .onboarding-step-marker {
  color: #15803d;
  background: #dcfce7;
}

.onboarding-step-content {
  flex: 1;
  min-width: 0;
}

.onboarding-step-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.onboarding-step h3 {
  margin: 0;
  color: #1e293b;
  font-size: 14px;
}

.onboarding-step-state {
  color: #94a3b8;
  font-size: 11px;
  white-space: nowrap;
}

.onboarding-step--current .onboarding-step-state {
  color: #2563eb;
  font-weight: 700;
}

.onboarding-step--done .onboarding-step-state {
  color: #15803d;
}

.onboarding-step p {
  margin: 5px 0 0;
  overflow: hidden;
  color: #64748b;
  font-size: 12px;
  line-height: 1.5;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.onboarding-step-error {
  display: block;
  margin-top: 4px;
  color: #b45309;
  font-size: 12px;
}

.onboarding-step :deep(.el-button svg) {
  margin-left: 4px;
}

.onboarding-spinner {
  animation: onboarding-spin 900ms linear infinite;
}

.onboarding-safety-note {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  color: #64748b;
  font-size: 12px;
  line-height: 1.5;
}

@keyframes onboarding-spin {
  to {
    transform: rotate(360deg);
  }
}

@media (max-width: 720px) {
  .personal-onboarding-card {
    padding: 20px 16px 16px;
  }

  .onboarding-header {
    display: block;
  }

  .onboarding-header :deep(.el-button) {
    margin-top: 12px;
  }

  .onboarding-step {
    align-items: flex-start;
  }

  .onboarding-step p {
    white-space: normal;
  }

  .onboarding-step :deep(.el-button) {
    align-self: center;
  }
}
</style>
