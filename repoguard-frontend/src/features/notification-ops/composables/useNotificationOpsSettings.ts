import { computed, getCurrentScope, onScopeDispose, reactive, ref, watch, type Ref } from "vue";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import { fetchSystemSettings, updateSystemSettings } from "@/api/config";
import type { NotificationSettings, SystemSettings } from "@/types";
import { getErrorMessage } from "@/utils/errors";
import { activeTenant } from "@/stores/tenantContext";
import { currentUser } from "@/stores/authState";

const defaultForm = (): NotificationSettings => ({ githubComment: true, highRiskPr: true, failedTask: true, email: "" });
const fields = ["githubComment", "highRiskPr", "failedTask", "email"] as const;

export const useNotificationOpsSettings = ({ canManage }: { canManage: Ref<boolean> }) => {
  const savingSettings = ref(false); const settingsLoading = ref(false);
  const loadErrorMessage = ref(""); const saveErrorMessage = ref("");
  const systemSettings = ref<SystemSettings>();
  const savedForm = ref(defaultForm());
  const notificationForm = reactive(defaultForm());
  const hasUnsavedChanges = computed(() => fields.some(key => notificationForm[key] !== savedForm.value[key]));
  let controller: AbortController | undefined;
  let disposed = false; let revision = 0;
  const cancelRead = () => { controller?.abort(); controller = undefined; settingsLoading.value = false; };
  const clearContext = () => {
    revision += 1; cancelRead(); systemSettings.value = undefined; savedForm.value = defaultForm();
    Object.assign(notificationForm, defaultForm()); loadErrorMessage.value = ""; saveErrorMessage.value = "";
  };
  watch([() => canManage.value, () => activeTenant.value, () => currentUser.value?.id], clearContext, { flush: "sync" });
  if (getCurrentScope()) onScopeDispose(() => { disposed = true; clearContext(); });
  const canSaveSettings = computed(() => !disposed && canManage.value && !!systemSettings.value
    && !savingSettings.value && !settingsLoading.value && !loadErrorMessage.value && hasUnsavedChanges.value);

  const applySystemSettings = (settings: SystemSettings, baseline: NotificationSettings) => {
    const received = { ...settings.notification, email: settings.notification.email ?? "" };
    const edits = Object.fromEntries(fields.filter(key => notificationForm[key] !== baseline[key])
      .map(key => [key, notificationForm[key]]));
    systemSettings.value = settings; savedForm.value = received;
    Object.assign(notificationForm, received, edits);
  };
  const loadSystemSettings = async () => {
    if (disposed || !canManage.value || savingSettings.value) return;
    cancelRead();
    const currentController = new AbortController(); controller = currentController;
    const version = revision; const baseline = { ...savedForm.value };
    const current = () => !disposed && version === revision && canManage.value
      && controller === currentController && !currentController.signal.aborted;
    settingsLoading.value = true; loadErrorMessage.value = "";
    try {
      const settings = await fetchSystemSettings({ signal: currentController.signal });
      if (current()) applySystemSettings(settings, baseline);
    } catch (error) {
      if (current()) loadErrorMessage.value = getErrorMessage(error, "通知设置加载失败");
    } finally { if (current()) settingsLoading.value = false; }
  };
  const saveNotificationSettings = async () => {
    if (!canSaveSettings.value) return;
    cancelRead();
    const version = revision; const submitted = { ...notificationForm };
    const current = () => !disposed && version === revision && canManage.value;
    savingSettings.value = true; saveErrorMessage.value = "";
    try {
      const saved = await updateSystemSettings({
        base: { ...systemSettings.value!.base }, policy: { ...systemSettings.value!.policy },
        security: { ...systemSettings.value!.security }, notification: submitted
      });
      if (!current()) return;
      applySystemSettings(saved, submitted);
      ElMessage.success(hasUnsavedChanges.value ? "本次提交已保存，新的修改尚未保存" : "通知设置已保存");
    } catch (error) {
      if (current()) { saveErrorMessage.value = getErrorMessage(error, "通知设置保存失败"); ElMessage.error(saveErrorMessage.value); }
    } finally { savingSettings.value = false; }
  };
  return { notificationForm, savingSettings, settingsLoading, systemSettings, hasUnsavedChanges,
    canSaveSettings, loadErrorMessage, saveErrorMessage, loadSystemSettings, saveNotificationSettings };
};
