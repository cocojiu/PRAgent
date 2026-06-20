import type { Ref } from "vue";
import { ref } from "vue";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import { triggerManualReview } from "@/api/reviews";
import type { GithubPullRequestOption } from "@/types";
import { getErrorMessage } from "@/utils/errors";
import { resolvePullRequestHeadSha } from "./useReviewTaskPullRequestPicker";

type UseReviewTaskCreationOptions = {
  canManage: Ref<boolean>;
  onCreated: (taskId: number) => Promise<void>;
  pullRequestOrganization: Ref<string>;
  pullRequestRepository: Ref<string>;
  selectedPullRequest: Readonly<Ref<GithubPullRequestOption | undefined>>;
};

export const useReviewTaskCreation = ({
  canManage,
  onCreated,
  pullRequestOrganization,
  pullRequestRepository,
  selectedPullRequest
}: UseReviewTaskCreationOptions) => {
  const creatingTask = ref(false);

  const createReviewFromSelectedPullRequest = async () => {
    if (!canManage.value) {
      return;
    }
    const pullRequest = selectedPullRequest.value;
    if (!pullRequest || !pullRequestOrganization.value || !pullRequestRepository.value) {
      ElMessage.warning("请选择一个有效的 GitHub PR");
      return;
    }
    creatingTask.value = true;
    try {
      const response = await triggerManualReview({
        organization: pullRequestOrganization.value,
        repository: pullRequestRepository.value,
        prNumber: pullRequest.number,
        title: pullRequest.title,
        commit: resolvePullRequestHeadSha(pullRequest),
        branch: pullRequest.branch,
        source: "github_pr_picker"
      });
      if (response.existing) {
        ElMessage.info("该 PR commit 已有审查任务，已跳转到详情页");
      } else {
        ElMessage.success(response.message || "审查任务已创建");
      }
      await onCreated(response.taskId);
    } catch (error) {
      ElMessage.error(getErrorMessage(error, "请求失败"));
    } finally {
      creatingTask.value = false;
    }
  };

  return {
    creatingTask,
    createReviewFromSelectedPullRequest
  };
};
