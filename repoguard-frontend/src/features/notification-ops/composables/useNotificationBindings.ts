import { computed, getCurrentScope, onScopeDispose, reactive, ref, watch } from "vue";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import {
  createNotificationBinding,
  deleteNotificationBinding,
  fetchNotificationBindings,
  testNotificationBinding,
  updateNotificationBinding,
  updateNotificationBindingStatus
} from "@/api/config";
import { canManage, currentUser } from "@/stores/authState";
import { activeTenant } from "@/stores/tenantContext";
import type { NotificationBinding, NotificationBindingRequest } from "@/types";
import { getErrorMessage } from "@/utils/errors";

const defaultBindingForm = (): NotificationBindingRequest => ({
  name: "",
  provider: "DINGTALK",
  organization: "",
  repository: "",
  enabled: true,
  webhookUrl: "",
  secret: "",
  notifyReviewCompleted: true,
  notifyReviewFailed: true,
  notifyHumanReviewRequired: true,
  notifyGithubComment: true
});

const toBindingForm = (binding?: NotificationBinding): NotificationBindingRequest => ({
  name: binding?.name ?? "",
  provider: binding?.provider ?? "DINGTALK",
  organization: binding?.organization ?? "",
  repository: binding?.repository ?? "",
  enabled: binding?.enabled ?? true,
  webhookUrl: binding?.webhookUrl ?? "",
  secret: binding?.secret ?? "",
  notifyReviewCompleted: binding?.notifyReviewCompleted ?? true,
  notifyReviewFailed: binding?.notifyReviewFailed ?? true,
  notifyHumanReviewRequired: binding?.notifyHumanReviewRequired ?? true,
  notifyGithubComment: binding?.notifyGithubComment ?? true
});

export const useNotificationBindings = () => {
  const notificationBindings = ref<NotificationBinding[]>([]);
  const bindingPage = ref(1);
  const bindingPageSize = ref(20);
  const bindingTotal = ref(0);
  const bindingsLoading = ref(false);
  const bindingLoadError = ref("");
  const bindingsNeedRefresh = ref(true);
  const bindingDialogVisible = ref(false);
  const savingBinding = ref(false);
  const testingBindingId = ref<number>();
  const editingBindingId = ref<number>();
  const bindingForm = reactive<NotificationBindingRequest>(defaultBindingForm());
  let disposed = false;
  let contextRevision = 0;
  let readController: AbortController | undefined;
  const bindingsCurrent = computed(() => !disposed && canManage.value && !bindingsLoading.value
    && !bindingsNeedRefresh.value && !bindingLoadError.value);
  const invalidateBindings = (clear = true) => {
    readController?.abort(); readController = undefined;
    bindingsLoading.value = false; bindingsNeedRefresh.value = true;
    if (clear) { notificationBindings.value = []; bindingTotal.value = 0; bindingLoadError.value = ""; }
  };
  watch([bindingPage, bindingPageSize], () => invalidateBindings(), { flush: "sync" });
  watch([canManage, activeTenant, () => currentUser.value?.id], () => {
    contextRevision += 1; invalidateBindings();
  }, { flush: "sync" });
  if (getCurrentScope()) onScopeDispose(() => { disposed = true; contextRevision += 1; invalidateBindings(); });

  const upsertBinding = (binding: NotificationBinding) => {
    const index = notificationBindings.value.findIndex((item) => item.id === binding.id);
    if (index >= 0) {
      notificationBindings.value[index] = binding;
      return;
    }
    notificationBindings.value = [binding, ...notificationBindings.value];
  };

  const loadNotificationBindings = async (): Promise<boolean> => {
    if (disposed || !canManage.value) return false;
    invalidateBindings(false); bindingLoadError.value = "";
    const controller = new AbortController(); readController = controller;
    const version = contextRevision;
    const page = bindingPage.value; const pageSize = bindingPageSize.value;
    const current = () => !disposed && canManage.value && version === contextRevision
      && readController === controller && !controller.signal.aborted;
    bindingsLoading.value = true;
    try {
      const result = await fetchNotificationBindings({ page, pageSize }, { signal: controller.signal });
      if (!current()) return false;
      const lastPage = Math.max(1, Math.ceil(result.total / pageSize));
      if (page > lastPage) {
        bindingPage.value = lastPage;
        return await loadNotificationBindings();
      }
      notificationBindings.value = result.items;
      bindingTotal.value = result.total;
      bindingsNeedRefresh.value = false;
      return true;
    } catch (error) {
      if (current()) bindingLoadError.value = getErrorMessage(error, "渠道绑定加载失败");
      return false;
    } finally {
      if (current()) bindingsLoading.value = false;
    }
  };

  const openBindingDialog = (binding?: NotificationBinding) => {
    editingBindingId.value = binding?.id;
    Object.assign(bindingForm, toBindingForm(binding));
    bindingDialogVisible.value = true;
  };

  const saveBinding = async () => {
    if (!canManage.value || savingBinding.value) {
      return;
    }
    savingBinding.value = true;
    try {
      const saved = editingBindingId.value
        ? await updateNotificationBinding(editingBindingId.value, { ...bindingForm })
        : await createNotificationBinding({ ...bindingForm });
      if (!editingBindingId.value) {
        bindingPage.value = 1;
      }
      upsertBinding(saved);
      await loadNotificationBindings();
      bindingDialogVisible.value = false;
      ElMessage.success("消息通知绑定已保存");
    } catch (error) {
      ElMessage.error(getErrorMessage(error, "消息通知绑定保存失败"));
    } finally {
      savingBinding.value = false;
    }
  };

  const runBindingTest = async (id: number) => {
    if (testingBindingId.value) {
      return;
    }
    testingBindingId.value = id;
    try {
      const result = await testNotificationBinding(id);
      ElMessage[result.success ? "success" : "error"](result.message);
      await loadNotificationBindings();
    } catch (error) {
      ElMessage.error(getErrorMessage(error, "消息通知测试失败"));
    } finally {
      testingBindingId.value = undefined;
    }
  };

  const toggleBinding = async (binding: NotificationBinding) => {
    try {
      const updated = await updateNotificationBindingStatus(binding.id, { enabled: !binding.enabled });
      upsertBinding(updated);
    } catch (error) {
      ElMessage.error(getErrorMessage(error, "消息通知状态更新失败"));
    }
  };

  const removeBinding = async (id: number) => {
    try {
      await deleteNotificationBinding(id);
      notificationBindings.value = notificationBindings.value.filter((binding) => binding.id !== id);
      bindingTotal.value = Math.max(0, bindingTotal.value - 1);
      if (notificationBindings.value.length === 0 && bindingPage.value > 1) {
        bindingPage.value -= 1;
      }
      await loadNotificationBindings();
      ElMessage.success("消息通知绑定已删除");
    } catch (error) {
      ElMessage.error(getErrorMessage(error, "消息通知绑定删除失败"));
    }
  };

  const changeBindingPage = async (page: number) => {
    bindingPage.value = Math.max(1, page);
    await loadNotificationBindings();
  };

  const changeBindingPageSize = async (pageSize: number) => {
    bindingPageSize.value = Math.max(1, pageSize);
    bindingPage.value = 1;
    await loadNotificationBindings();
  };

  return {
    notificationBindings,
    bindingPage,
    bindingPageSize,
    bindingTotal,
    bindingsLoading,
    bindingLoadError,
    bindingsNeedRefresh,
    bindingsCurrent,
    bindingDialogVisible,
    savingBinding,
    testingBindingId,
    editingBindingId,
    bindingForm,
    loadNotificationBindings,
    openBindingDialog,
    saveBinding,
    runBindingTest,
    toggleBinding,
    removeBinding,
    changeBindingPage,
    changeBindingPageSize
  };
};
