import { ref, type Ref } from "vue";
import type {
  GithubChecksPolicyRequest,
  GithubChecksPreviewRequest,
  GithubChecksSetupStatus
} from "@/types";
import {
  fetchGithubChecksSetup,
  previewGithubChecks,
  updateGithubChecksPolicy
} from "@/api/config";
import { getErrorMessage } from "@/utils/errors";

type SetupRequests = {
  fetch: (organization: string, repository: string) => Promise<GithubChecksSetupStatus>;
  preview: (payload: GithubChecksPreviewRequest) => Promise<GithubChecksSetupStatus>;
  updatePolicy: (payload: GithubChecksPolicyRequest) => Promise<GithubChecksSetupStatus>;
};

type SetupOptions = {
  canManage: Ref<boolean>;
  requests?: SetupRequests;
};

const defaultRequests: SetupRequests = {
  fetch: fetchGithubChecksSetup,
  preview: previewGithubChecks,
  updatePolicy: updateGithubChecksPolicy
};

export const useGithubChecksSetup = ({ canManage, requests = defaultRequests }: SetupOptions) => {
  const organization = ref("");
  const repository = ref("");
  const pullRequestNumber = ref<number>();
  const status = ref<GithubChecksSetupStatus>();
  const loading = ref(false);
  const previewing = ref(false);
  const saving = ref(false);
  const errorMessage = ref("");

  const validTarget = () => organization.value.trim().length > 0 && repository.value.trim().length > 0;

  const load = async () => {
    if (!validTarget() || loading.value) {
      return;
    }
    loading.value = true;
    errorMessage.value = "";
    try {
      status.value = await requests.fetch(organization.value.trim(), repository.value.trim());
    } catch (error) {
      errorMessage.value = getErrorMessage(error, "GitHub Checks 自检失败");
    } finally {
      loading.value = false;
    }
  };

  const preview = async () => {
    if (!canManage.value || !validTarget() || !pullRequestNumber.value || previewing.value) {
      return;
    }
    previewing.value = true;
    errorMessage.value = "";
    try {
      status.value = await requests.preview({
        organization: organization.value.trim(),
        repository: repository.value.trim(),
        pullRequestNumber: pullRequestNumber.value
      });
    } catch (error) {
      errorMessage.value = getErrorMessage(error, "Check Run 预览失败");
    } finally {
      previewing.value = false;
    }
  };

  const setEnabled = async (enabled: boolean) => {
    if (!canManage.value || !validTarget() || !status.value || saving.value) {
      return;
    }
    saving.value = true;
    errorMessage.value = "";
    try {
      status.value = await requests.updatePolicy({
        organization: organization.value.trim(),
        repository: repository.value.trim(),
        enabled,
        expectedVersion: status.value.policyVersion,
        confirmed: true
      });
    } catch (error) {
      errorMessage.value = getErrorMessage(error, enabled ? "启用 Check Run 失败" : "停用 Check Run 失败");
    } finally {
      saving.value = false;
    }
  };

  return {
    organization,
    repository,
    pullRequestNumber,
    status,
    loading,
    previewing,
    saving,
    errorMessage,
    load,
    preview,
    setEnabled
  };
};
