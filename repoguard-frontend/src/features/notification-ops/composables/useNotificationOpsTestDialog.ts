import { computed, getCurrentScope, onScopeDispose, ref, watch, type Ref } from "vue";
import type { NotificationBinding } from "@/types";
import { currentUser } from "@/stores/authState";
import { activeTenant } from "@/stores/tenantContext";
import { getErrorMessage } from "@/utils/errors";
import { notificationBindingTestSignature, type NotificationBindingTestOutcome } from "./useNotificationBindings";

type UseNotificationOpsTestDialogOptions = {
  notificationBindings: Ref<NotificationBinding[]>;
  canManage: Ref<boolean>;
  canTestBinding: (id: number) => boolean;
  runBindingTest: (id: number, isCurrent: () => boolean) => Promise<NotificationBindingTestOutcome>;
};

export const useNotificationOpsTestDialog = ({
  notificationBindings,
  canManage,
  canTestBinding,
  runBindingTest
}: UseNotificationOpsTestDialogOptions) => {
  const testDialogVisible = ref(false);
  const selectedTestBindingId = ref<number>();
  const sendingTest = ref(false);
  const testErrorMessage = ref(""); const testInfoMessage = ref("");
  let disposed = false; let revision = 0;
  const enabledNotificationBindings = computed(() => notificationBindings.value.filter((binding) => binding.enabled));
  const selectedBinding = computed(() => enabledNotificationBindings.value.find(binding => binding.id === selectedTestBindingId.value));
  const invalidate = () => { revision += 1; testErrorMessage.value = ""; testInfoMessage.value = ""; };
  watch([testDialogVisible, selectedTestBindingId, () => notificationBindingTestSignature(selectedBinding.value, false)], invalidate, { flush: "sync" });
  watch([canManage, activeTenant, () => currentUser.value?.id], () => {
    invalidate(); testDialogVisible.value = false; selectedTestBindingId.value = undefined;
  }, { flush: "sync" });
  if (getCurrentScope()) onScopeDispose(() => { disposed = true; invalidate(); testDialogVisible.value = false; selectedTestBindingId.value = undefined; });
  const canRunSelectedBindingTest = computed(() => !disposed && canManage.value && testDialogVisible.value && !sendingTest.value
    && !!selectedBinding.value && canTestBinding(selectedBinding.value.id));

  const openTestDialog = () => {
    if (disposed || !canManage.value) return;
    const first = enabledNotificationBindings.value[0];
    if (!first) return;
    invalidate(); selectedTestBindingId.value = first.id;
    testDialogVisible.value = true;
  };

  const runSelectedBindingTest = async () => {
    if (!canRunSelectedBindingTest.value) return;
    const id = selectedTestBindingId.value!; const version = revision;
    const current = () => !disposed && version === revision && canManage.value && testDialogVisible.value && selectedTestBindingId.value === id;
    sendingTest.value = true; testErrorMessage.value = ""; testInfoMessage.value = "";
    try {
      const outcome = await runBindingTest(id, current);
      if (!current()) return;
      if (outcome.status === "success" && outcome.refreshed) testDialogVisible.value = false;
      else if (outcome.status === "failure") testErrorMessage.value = outcome.message;
      else if (outcome.status === "success") testInfoMessage.value = "测试发送已成功，自动刷新渠道列表失败。";
      else testInfoMessage.value = "渠道正在处理中或状态已变化，请刷新后再试。";
    } catch (error) { if (current()) testErrorMessage.value = getErrorMessage(error, "消息通知测试失败"); }
    finally { sendingTest.value = false; }
  };

  return {
    enabledNotificationBindings,
    selectedTestBindingId,
    testDialogVisible,
    sendingTest,
    testErrorMessage,
    testInfoMessage,
    canRunSelectedBindingTest,
    openTestDialog,
    runSelectedBindingTest
  };
};
