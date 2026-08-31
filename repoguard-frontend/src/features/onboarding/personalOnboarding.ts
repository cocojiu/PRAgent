import type { GithubIntegrationConfig, ReviewPolicyConfig } from "@/types";

export type PersonalOnboardingStepId = "github" | "llm" | "connections" | "preview";
export type PersonalOnboardingStepState = "done" | "current" | "pending";

export type PersonalOnboardingStep = {
  id: PersonalOnboardingStepId;
  title: string;
  description: string;
  state: PersonalOnboardingStepState;
  actionLabel?: string;
  actionEnabled: boolean;
};

export type PersonalOnboardingProgress = {
  githubConfigured: boolean;
  llmConfigured: boolean;
  githubConnectionVerified: boolean;
  llmConnectionVerified: boolean;
  repositoryChecked: boolean;
  pullRequestCount: number | undefined;
  previewLaunched: boolean;
};

const hasConfiguredSecret = (secretStatus?: string, secretValue?: string) =>
  secretStatus === "configured" || (secretStatus == null && Boolean(secretValue?.trim()));

/**
 * GitHub is ready only when both credentials and the first repository are present.
 * The API deliberately returns a masked token, so status/secretStatus is authoritative.
 */
export const isGithubSetupComplete = (config?: GithubIntegrationConfig) =>
  config?.status === "configured"
  && config.secretStatus !== "key_mismatch"
  && config.secretStatus !== "decrypt_failed"
  && hasConfiguredSecret(config.secretStatus, config.token)
  && Boolean(config.defaultOwner?.trim())
  && Boolean(config.defaultRepo?.trim());

/**
 * A mock provider is useful for local smoke checks and intentionally has no external key.
 * Every real provider still requires a configured secret before the first review.
 */
export const isLlmSetupComplete = (config?: ReviewPolicyConfig) => {
  if (!config?.llmEnabled || !config.llmProvider?.trim() || !config.modelName?.trim()) {
    return false;
  }
  if (config.llmProvider.trim().toLowerCase() === "mock") {
    return true;
  }
  return config.secretStatus !== "key_mismatch"
    && config.secretStatus !== "decrypt_failed"
    && hasConfiguredSecret(config.secretStatus, config.apiKey);
};

export const buildPersonalOnboardingSteps = ({
  githubConfigured,
  llmConfigured,
  githubConnectionVerified,
  llmConnectionVerified,
  repositoryChecked,
  pullRequestCount,
  previewLaunched
}: PersonalOnboardingProgress): PersonalOnboardingStep[] => {
  const connectionsVerified = githubConnectionVerified && llmConnectionVerified && repositoryChecked;
  return [
    {
      id: "github",
      title: "配置 GitHub PAT 和首个仓库",
      description: githubConfigured
        ? "GitHub 凭据和默认仓库已就绪。"
        : "填写 Token、Owner 和 Repo；只需要一个可读取 PR 的仓库。",
      state: githubConfigured ? "done" : "current",
      actionLabel: githubConfigured ? undefined : "去配置 GitHub",
      actionEnabled: !githubConfigured
    },
    {
      id: "llm",
      title: "配置一个 LLM provider 和模型",
      description: llmConfigured
        ? "LLM provider、模型和密钥已就绪。"
        : "选择 provider 和模型并填写 API Key；其他高级参数可保持默认。",
      state: !githubConfigured ? "pending" : llmConfigured ? "done" : "current",
      actionLabel: llmConfigured ? undefined : "去配置 LLM",
      actionEnabled: githubConfigured && !llmConfigured
    },
    {
      id: "connections",
      title: "验证权限、模型和仓库连通性",
      description: connectionsVerified
        ? `GitHub、LLM 和仓库检查均通过${pullRequestCount == null ? "" : `，发现 ${pullRequestCount} 个 open PR`}。`
        : "一次检查会验证 GitHub Token、LLM 模型和默认仓库，不会发布评论。",
      state: !githubConfigured || !llmConfigured ? "pending" : connectionsVerified ? "done" : "current",
      actionLabel: connectionsVerified ? "重新检查" : "开始检查",
      actionEnabled: githubConfigured && llmConfigured
    },
    {
      id: "preview",
      title: "选择一个 PR 发起预览审查",
      description: previewLaunched
        ? "已打开预览审查入口；评论发布仍需在任务详情页明确确认。"
        : pullRequestCount === 0
        ? "连接正常但默认仓库暂无 open PR，请先创建一个 PR。"
        : "先看审查结果和评论预览，确认后才允许向 GitHub 发布。",
      state: !connectionsVerified ? "pending" : previewLaunched ? "done" : "current",
      actionLabel: previewLaunched ? "再次打开预览" : "开始预览审查",
      actionEnabled: connectionsVerified && (pullRequestCount == null || pullRequestCount > 0)
    }
  ];
};
