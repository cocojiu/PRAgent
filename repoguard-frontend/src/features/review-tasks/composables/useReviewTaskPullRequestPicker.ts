import { computed, ref } from "vue";
import { fetchGithubPullRequestOptions } from "@/api/reviews";
import type { GithubPullRequestOption } from "@/types";
import { getErrorMessage } from "@/utils/errors";

export const resolvePullRequestHeadSha = (pullRequest: GithubPullRequestOption) => pullRequest.headSha || pullRequest.commit;

export const useReviewTaskPullRequestPicker = () => {
  const pullRequestOptions = ref<GithubPullRequestOption[]>([]);
  const loadingPullRequests = ref(false);
  const pullRequestsLoaded = ref(false);
  const pullRequestError = ref("");
  const pullRequestOrganization = ref("");
  const pullRequestRepository = ref("");
  const selectedPullRequestNumber = ref<number>();
  let pullRequestSeq = 0;

  const selectedPullRequest = computed(() =>
    pullRequestOptions.value.find((item) => item.number === selectedPullRequestNumber.value)
  );

  const pullRequestRepositoryText = computed(() => {
    if (!pullRequestOrganization.value || !pullRequestRepository.value) {
      return "使用集成配置中的 GitHub 仓库";
    }
    return `${pullRequestOrganization.value} / ${pullRequestRepository.value}`;
  });

  const ensureDefaultPullRequestSelected = () => {
    if (!selectedPullRequestNumber.value && pullRequestOptions.value.length) {
      selectedPullRequestNumber.value = pullRequestOptions.value[0].number;
    }
  };

  const loadPullRequests = async (options: { preselect?: boolean } = {}) => {
    const requestSeq = ++pullRequestSeq;
    loadingPullRequests.value = true;
    pullRequestError.value = "";
    try {
      const response = await fetchGithubPullRequestOptions();
      if (requestSeq !== pullRequestSeq) {
        return;
      }
      pullRequestOrganization.value = response.organization ?? "";
      pullRequestRepository.value = response.repository ?? "";
      pullRequestOptions.value = response.items;
      pullRequestsLoaded.value = true;
      if (options.preselect !== false) {
        ensureDefaultPullRequestSelected();
      }
    } catch (error) {
      if (requestSeq !== pullRequestSeq) {
        return;
      }
      pullRequestOptions.value = [];
      pullRequestsLoaded.value = false;
      pullRequestError.value = getErrorMessage(error, "GitHub PR 列表加载失败");
    } finally {
      if (requestSeq === pullRequestSeq) {
        loadingPullRequests.value = false;
      }
    }
  };

  const reloadPullRequests = () => {
    selectedPullRequestNumber.value = undefined;
    void loadPullRequests();
  };

  const selectPullRequest = (row?: GithubPullRequestOption) => {
    selectedPullRequestNumber.value = row?.number;
  };

  return {
    loadingPullRequests,
    pullRequestError,
    pullRequestOptions,
    pullRequestOrganization,
    pullRequestRepository,
    pullRequestRepositoryText,
    pullRequestsLoaded,
    selectedPullRequest,
    selectedPullRequestNumber,
    ensureDefaultPullRequestSelected,
    loadPullRequests,
    reloadPullRequests,
    selectPullRequest
  };
};
