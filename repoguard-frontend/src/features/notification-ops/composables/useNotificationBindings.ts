import { reactive, ref } from "vue";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import {
  createNotificationBinding,
  deleteNotificationBinding,
  fetchNotificationBindings,
  testNotificationBinding,
  updateNotificationBinding,
  updateNotificationBindingStatus
} from "@/api/config";
import { canManage } from "@/stores/authState";
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
  const bindingsLoading = ref(false);
  const bindingDialogVisible = ref(false);
  const savingBinding = ref(false);
  const testingBindingId = ref<number>();
  const editingBindingId = ref<number>();
  const bindingForm = reactive<NotificationBindingRequest>(defaultBindingForm());

  const upsertBinding = (binding: NotificationBinding) => {
    const index = notificationBindings.value.findIndex((item) => item.id === binding.id);
    if (index >= 0) {
      notificationBindings.value[index] = binding;
      return;
    }
    notificationBindings.value = [binding, ...notificationBindings.value];
  };

  const loadNotificationBindings = async () => {
    bindingsLoading.value = true;
    try {
      const result = await fetchNotificationBindings();
      notificationBindings.value = result.items;
    } catch (error) {
      ElMessage.error(getErrorMessage(error, "渠道绑定加载失败"));
    } finally {
      bindingsLoading.value = false;
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
      upsertBinding(saved);
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
      ElMessage.success("消息通知绑定已删除");
    } catch (error) {
      ElMessage.error(getErrorMessage(error, "消息通知绑定删除失败"));
    }
  };

  return {
    notificationBindings,
    bindingsLoading,
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
    removeBinding
  };
};
