import { computed, ref, type Ref } from "vue";
import type { NotificationBinding } from "@/types";

type UseNotificationOpsTestDialogOptions = {
  notificationBindings: Ref<NotificationBinding[]>;
  runBindingTest: (id: number) => Promise<void>;
};

export const useNotificationOpsTestDialog = ({
  notificationBindings,
  runBindingTest
}: UseNotificationOpsTestDialogOptions) => {
  const testDialogVisible = ref(false);
  const selectedTestBindingId = ref<number>();
  const enabledNotificationBindings = computed(() => notificationBindings.value.filter((binding) => binding.enabled));

  const openTestDialog = () => {
    selectedTestBindingId.value = enabledNotificationBindings.value[0]?.id;
    testDialogVisible.value = true;
  };

  const runSelectedBindingTest = async () => {
    if (!selectedTestBindingId.value) {
      return;
    }
    await runBindingTest(selectedTestBindingId.value);
    testDialogVisible.value = false;
  };

  return {
    enabledNotificationBindings,
    selectedTestBindingId,
    testDialogVisible,
    openTestDialog,
    runSelectedBindingTest
  };
};
