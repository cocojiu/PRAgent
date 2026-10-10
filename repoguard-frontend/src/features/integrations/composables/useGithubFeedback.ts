import { getCurrentScope, onScopeDispose, ref, watch, type Ref } from "vue";
import type { ApiRequestOptions } from "@/api/contracts";
import { fetchGithubFeedback, retryGithubFeedback } from "@/api/config";
import { activeTenant } from "@/stores/tenantContext";
import type { GithubFeedbackDiagnostics } from "@/types";
import { getErrorMessage } from "@/utils/errors";

type FeedbackRequests = {
  fetch: (limit: number, options?: ApiRequestOptions) => Promise<GithubFeedbackDiagnostics>;
  retry: (id: number) => Promise<void>;
};

export const useGithubFeedback = ({ canManage, requests = { fetch: fetchGithubFeedback, retry: retryGithubFeedback } }:
  { canManage: Ref<boolean>; requests?: FeedbackRequests }) => {
  const diagnostics = ref<GithubFeedbackDiagnostics | null>(null);
  const error = ref("");
  const retryError = ref("");
  const retryMessage = ref("");
  const loading = ref(false);
  const retrying = ref<number | null>(null);
  const stale = ref(false);
  let controller: AbortController | undefined;
  let disposed = false;
  let revision = 0;
  const cancelRead = () => {
    controller?.abort(); controller = undefined; loading.value = false;
    stale.value = !!diagnostics.value;
  };
  const clearContext = () => {
    revision += 1; cancelRead(); diagnostics.value = null; stale.value = false;
    error.value = ""; retryError.value = ""; retryMessage.value = "";
  };
  watch([() => canManage.value, () => activeTenant.value], clearContext, { flush: "sync" });
  if (getCurrentScope()) onScopeDispose(() => { disposed = true; clearContext(); });

  const load = async () => {
    if (disposed || !canManage.value) return;
    cancelRead();
    const currentController = new AbortController(); controller = currentController;
    const version = revision;
    const current = () => !disposed && canManage.value && revision === version
      && controller === currentController && !currentController.signal.aborted;
    loading.value = true; error.value = "";
    try {
      const result = await requests.fetch(20, { signal: currentController.signal });
      if (current()) { diagnostics.value = result; stale.value = false; }
    } catch (reason) {
      if (current()) error.value = getErrorMessage(reason, "反馈状态加载失败");
    } finally {
      if (current()) loading.value = false;
    }
  };

  const canRetry = (id: number) => !disposed && canManage.value && retrying.value === null
    && !loading.value && !stale.value && !error.value && diagnostics.value?.enabled === true
    && diagnostics.value.events.some((event) => event.id === id && event.status === "FAILED");

  const retry = async (id: number) => {
    if (!canRetry(id)) return;
    cancelRead(); retrying.value = id; retryError.value = ""; retryMessage.value = "";
    const version = revision;
    const current = () => !disposed && canManage.value && revision === version;
    try {
      await requests.retry(id);
      if (!current()) return;
      retryMessage.value = `事件 ${id} 已重新入队，处理结果以最新状态为准。`;
      await load();
    } catch (reason) {
      if (current()) { cancelRead(); retryError.value = getErrorMessage(reason, "反馈重试失败"); }
    } finally {
      if (!disposed) retrying.value = null;
    }
  };

  return { diagnostics, error, retryError, retryMessage, loading, retrying, stale, load, retry, canRetry };
};
