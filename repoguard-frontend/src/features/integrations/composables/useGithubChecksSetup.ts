import { computed, getCurrentScope, onScopeDispose, ref, watch, type Ref } from "vue";
import type { ApiRequestOptions } from "@/api/contracts";
import { activeTenant } from "@/stores/tenantContext";
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
  fetch: (organization: string, repository: string, options?: ApiRequestOptions) => Promise<GithubChecksSetupStatus>;
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
  const confirming = ref(false);
  const previewTarget = ref<number>();
  let controller: AbortController | undefined;
  let disposed = false;
  let revision = 0;
  let previewRevision = 0;
  const target = () => ({ organization: organization.value.trim(), repository: repository.value.trim() });
  const matches = (value: GithubChecksSetupStatus, context: ReturnType<typeof target>) =>
    value.organization.toLowerCase() === context.organization.toLowerCase()
    && value.repository.toLowerCase() === context.repository.toLowerCase();
  const clearEvidence = () => {
    revision += 1;
    controller?.abort();
    controller = undefined;
    loading.value = false;
    status.value = undefined;
    previewTarget.value = undefined;
    errorMessage.value = "";
  };
  watch([() => organization.value.trim().toLowerCase(), () => repository.value.trim().toLowerCase(),
    () => canManage.value, () => activeTenant.value], clearEvidence, { flush: "sync" });
  watch(pullRequestNumber, () => { previewRevision += 1; previewTarget.value = undefined; }, { flush: "sync" });
  if (getCurrentScope()) onScopeDispose(() => { disposed = true; clearEvidence(); });
  const previewMatchesTarget = computed(() => previewTarget.value !== undefined && previewTarget.value === pullRequestNumber.value);
  const evidenceReady = () => !disposed && canManage.value && !!status.value && matches(status.value, target())
    && !loading.value && !previewing.value && !saving.value && !errorMessage.value;
  const canEnable = computed(() => evidenceReady() && status.value!.ready && !status.value!.repositoryCheckRunEnabled && !confirming.value);
  const canDisable = computed(() => evidenceReady() && status.value!.repositoryCheckRunEnabled && !confirming.value);

  const validTarget = () => organization.value.trim().length > 0 && repository.value.trim().length > 0;

  const load = async () => {
    if (disposed || !canManage.value || !validTarget() || previewing.value || saving.value) {
      return;
    }
    clearEvidence();
    const currentController = new AbortController(); controller = currentController;
    const version = revision; const context = target();
    const current = () => !disposed && revision === version && controller === currentController && !currentController.signal.aborted;
    loading.value = true;
    errorMessage.value = "";
    try {
      const result = await requests.fetch(context.organization, context.repository, { signal: currentController.signal });
      if (!current()) return;
      if (!matches(result, context)) throw new Error("自检结果与当前仓库不一致，请重新自检");
      status.value = result;
    } catch (error) {
      if (current()) errorMessage.value = getErrorMessage(error, "GitHub Checks 自检失败");
    } finally {
      if (current()) loading.value = false;
    }
  };

  const preview = async () => {
    if (!evidenceReady() || confirming.value || !Number.isInteger(pullRequestNumber.value) || (pullRequestNumber.value ?? 0) < 1) {
      return;
    }
    previewing.value = true;
    const context = target(); const prNumber = pullRequestNumber.value!; const version = ++revision;
    const prVersion = previewRevision;
    previewTarget.value = undefined;
    const current = () => !disposed && revision === version && previewRevision === prVersion && canManage.value;
    errorMessage.value = "";
    try {
      const result = await requests.preview({
        ...context,
        pullRequestNumber: prNumber
      });
      if (!current()) return;
      if (!matches(result, context)) throw new Error("预览结果与当前仓库不一致，请重新自检");
      status.value = result;
      previewTarget.value = prNumber;
    } catch (error) {
      if (current()) errorMessage.value = getErrorMessage(error, "Check Run 预览失败");
    } finally {
      previewing.value = false;
    }
  };

  const setEnabled = async (enabled: boolean): Promise<boolean> => {
    if (!evidenceReady() || confirming.value || (enabled ? !status.value!.ready || status.value!.repositoryCheckRunEnabled : !status.value!.repositoryCheckRunEnabled)) {
      return false;
    }
    const context = target(); const expectedVersion = status.value!.policyVersion; const version = ++revision;
    const current = () => !disposed && revision === version && canManage.value;
    saving.value = true;
    errorMessage.value = "";
    try {
      const result = await requests.updatePolicy({
        ...context,
        enabled,
        expectedVersion,
        confirmed: true
      });
      if (!current()) return false;
      if (!matches(result, context) || result.repositoryCheckRunEnabled !== enabled) throw new Error("返回的仓库配置未确认本次更新，请重新自检");
      status.value = result;
      previewTarget.value = undefined;
      return true;
    } catch (error) {
      if (current()) errorMessage.value = getErrorMessage(error, enabled ? "启用 Check Run 失败" : "停用 Check Run 失败");
      return false;
    } finally {
      saving.value = false;
    }
  };

  const confirmPolicyChange = async (enabled: boolean, confirm: () => Promise<unknown>): Promise<boolean> => {
    if (enabled ? !canEnable.value : !canDisable.value) return false;
    const version = revision; const evidence = status.value;
    confirming.value = true;
    try { await confirm(); }
    catch { return false; }
    finally { confirming.value = false; }
    if (disposed || version !== revision || status.value !== evidence) return false;
    return setEnabled(enabled);
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
    confirming,
    canEnable,
    canDisable,
    previewMatchesTarget,
    load,
    preview,
    setEnabled,
    confirmPolicyChange
  };
};
