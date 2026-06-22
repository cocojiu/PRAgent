import { ElMessageBox } from "element-plus/es/components/message-box/index.mjs";
import type { ComputedRef, Ref } from "vue";
import type { GithubCommentPreview, GithubCommentWritebackCheck, ReviewTaskDetail } from "@/types";

type UseReviewDetailGithubCommentPublishConfirmOptions = {
  canManage: ComputedRef<boolean>;
  canPublishGithubComments: ComputedRef<boolean>;
  githubCommentPreview: Ref<GithubCommentPreview | null>;
  loadGithubCommentPreview: (id: number) => Promise<void>;
  loadGithubCommentPublicationHistory: (id: number) => Promise<void>;
  publishGithubCommentsForTask: (id: number, afterPublish?: () => Promise<void>) => Promise<void>;
  publishingComments: Ref<boolean>;
  selectedTask: Ref<ReviewTaskDetail | null>;
  writebackCheck: ComputedRef<GithubCommentWritebackCheck | undefined>;
};

export const useReviewDetailGithubCommentPublishConfirm = ({
  canManage,
  canPublishGithubComments,
  githubCommentPreview,
  loadGithubCommentPreview,
  loadGithubCommentPublicationHistory,
  publishGithubCommentsForTask,
  publishingComments,
  selectedTask,
  writebackCheck
}: UseReviewDetailGithubCommentPublishConfirmOptions) => {
  const confirmPublishGithubComments = async () => {
    const task = selectedTask.value;
    const preview = githubCommentPreview.value;
    if (!canManage.value || !task || publishingComments.value || !canPublishGithubComments.value || !preview) {
      return;
    }

    try {
      const warningText = writebackCheck.value && writebackCheck.value.status !== "ready"
        ? `\n\n提示：${writebackCheck.value.messages.join(" ")}`
        : "";
      await ElMessageBox.confirm(
        `将向 GitHub PR #${task.prNumber} 回写 ${preview.commentableCount} 条评论。确认继续？${warningText}`,
        "确认回写 GitHub 评论",
        {
          confirmButtonText: "确认回写",
          cancelButtonText: "取消",
          type: "warning"
        }
      );
    } catch {
      return;
    }

    const taskId = task.id;
    await publishGithubCommentsForTask(taskId, async () => {
      await Promise.all([
        loadGithubCommentPreview(taskId),
        loadGithubCommentPublicationHistory(taskId)
      ]);
    });
  };

  return {
    confirmPublishGithubComments
  };
};
