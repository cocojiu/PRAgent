import { reactive, ref, type Ref } from "vue";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import {
  fetchSystemSettings,
  updateSystemSettings
} from "@/api/config";
import type { NotificationSettings, SystemSettings } from "@/types";
import { getErrorMessage } from "@/utils/errors";

type UseNotificationOpsSettingsOptions = {
  canManage: Ref<boolean>;
};

export const useNotificationOpsSettings = ({ canManage }: UseNotificationOpsSettingsOptions) => {
  const savingSettings = ref(false);
  const systemSettings = ref<SystemSettings>();
  const notificationForm = reactive<NotificationSettings>({
    githubComment: true,
    highRiskPr: true,
    failedTask: true,
    email: ""
  });

  const applySystemSettings = (settings: SystemSettings) => {
    systemSettings.value = settings;
    Object.assign(notificationForm, {
      ...settings.notification,
      email: settings.notification.email ?? ""
    });
  };

  const loadSystemSettings = async () => {
    const settings = await fetchSystemSettings();
    applySystemSettings(settings);
  };

  const saveNotificationSettings = async () => {
    if (!canManage.value || !systemSettings.value || savingSettings.value) {
      return;
    }
    savingSettings.value = true;
    try {
      const saved = await updateSystemSettings({
        base: systemSettings.value.base,
        policy: systemSettings.value.policy,
        security: systemSettings.value.security,
        notification: { ...notificationForm }
      });
      applySystemSettings(saved);
      ElMessage.success("通知设置已保存");
    } catch (error) {
      ElMessage.error(getErrorMessage(error, "通知设置保存失败"));
    } finally {
      savingSettings.value = false;
    }
  };

  return {
    notificationForm,
    savingSettings,
    systemSettings,
    loadSystemSettings,
    saveNotificationSettings
  };
};
