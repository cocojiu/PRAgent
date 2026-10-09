import { computed, getCurrentScope, onScopeDispose, reactive, ref, watch, type Ref } from "vue";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import { createReviewRule, updateReviewRule, updateReviewRuleStatus } from "@/api/config";
import type {
  ReviewRuleConfig,
  ReviewRuleConfigRequest,
  RuleStatus
} from "@/types";
import { getErrorMessage } from "@/utils/errors";
import { currentUser } from "@/stores/authState";
import { activeTenant } from "@/stores/tenantContext";

type ReviewRuleEditorOptions = {
  canManage: Readonly<Ref<boolean>>;
  reloadRules: () => Promise<boolean | void>;
  rules: Ref<ReviewRuleConfig[]>;
  rulesCurrent?: Readonly<Ref<boolean>>;
  cancelRulesRead?: () => void;
  invalidateRules?: () => void;
};

const createEmptyRuleForm = (): ReviewRuleConfigRequest => ({
  id: "",
  name: "",
  scope: "",
  applicableLanguages: "",
  filePatterns: "",
  severity: "low",
  status: "disabled",
  confidence: 90,
  description: "",
  positiveExample: "",
  falsePositiveGuidance: "",
  enforcementMode: "comment",
  detectorType: "BUILTIN",
  matcherExpression: "",
  exceptionPatterns: ""
});

export const useReviewRuleEditor = ({ canManage, reloadRules, rules, rulesCurrent = ref(true),
  cancelRulesRead = () => undefined, invalidateRules = () => undefined }: ReviewRuleEditorOptions) => {
  const saving = ref(false);
  const pendingRuleIds = ref(new Set<string>());
  const pendingStatusIds = ref(new Set<string>());
  const statusSavingId = computed(() => [...pendingStatusIds.value][0] ?? "");
  const busyRuleIds = computed(() => [...pendingRuleIds.value]);
  const ruleOperationErrors = ref<Record<string, string>>({});
  const dialogVisible = ref(false);
  const editingRuleId = ref("");
  const editingPolicyVersion = ref(0);
  const ruleForm = reactive<ReviewRuleConfigRequest>(createEmptyRuleForm());
  const savedForm = ref(createEmptyRuleForm());
  const ruleFields = Object.keys(createEmptyRuleForm()) as (keyof ReviewRuleConfigRequest)[];
  const ruleSaveError = ref("");
  const ruleSaveNotice = ref("");
  const ruleRefreshError = ref("");
  const refreshingRule = ref(false);
  const ruleHasUnsavedChanges = computed(() => ruleFields.some(key => ruleForm[key] !== savedForm.value[key]));
  const latestEditingRule = computed(() => rules.value.find(rule => rule.id === (editingRuleId.value || ruleForm.id.trim().toUpperCase())));
  const ruleVersionChanged = computed(() => rulesCurrent.value && (editingRuleId.value
    ? !latestEditingRule.value || latestEditingRule.value.policyVersion !== editingPolicyVersion.value
    : !!latestEditingRule.value));
  let disposed = false; let contextRevision = 0; let editorRevision = 0;
  const contextIdentity = () => JSON.stringify([currentUser.value?.id, activeTenant.value]);
  watch([dialogVisible, editingRuleId, () => editingRuleId.value ? "" : ruleForm.id.trim().toUpperCase()],
    () => { editorRevision += 1; }, { flush: "sync" });
  const clearEditor = () => {
    dialogVisible.value = false; editingRuleId.value = ""; editingPolicyVersion.value = 0;
    savedForm.value = createEmptyRuleForm(); Object.assign(ruleForm, savedForm.value);
    ruleSaveError.value = ""; ruleSaveNotice.value = ""; ruleRefreshError.value = "";
    ruleOperationErrors.value = {};
  };
  watch([canManage, activeTenant, () => currentUser.value?.id], () => { contextRevision += 1; clearEditor(); }, { flush: "sync" });
  if (getCurrentScope()) onScopeDispose(() => { disposed = true; contextRevision += 1; clearEditor(); });
  const canSaveRule = computed(() => !disposed && canManage.value && rulesCurrent.value && dialogVisible.value
    && !saving.value && !refreshingRule.value && !ruleVersionChanged.value && ruleHasUnsavedChanges.value
    && !pendingRuleIds.value.has(editingRuleId.value || ruleForm.id.trim().toUpperCase()));
  const canChangeRule = (id: string) => !disposed && canManage.value && rulesCurrent.value
    && !pendingRuleIds.value.has(id) && rules.value.some(rule => rule.id === id);
  const canRefreshRule = computed(() => !disposed && canManage.value && dialogVisible.value && !saving.value && !refreshingRule.value
    && !!ruleForm.id.trim() && !pendingRuleIds.value.has(editingRuleId.value || ruleForm.id.trim().toUpperCase()));
  const finishRuleOperation = (id: string, version: number, context: string) => {
    pendingRuleIds.value.delete(id); pendingStatusIds.value.delete(id);
    if (!disposed && version !== contextRevision && context === contextIdentity()) invalidateRules();
  };

  const resetForm = (rule?: ReviewRuleConfig) => {
    ruleForm.id = rule?.id ?? "";
    ruleForm.name = rule?.name ?? "";
    ruleForm.scope = rule?.scope ?? "Java Patch";
    ruleForm.applicableLanguages = rule?.applicableLanguages ?? "";
    ruleForm.filePatterns = rule?.filePatterns ?? "";
    ruleForm.severity = rule?.severity ?? "low";
    ruleForm.status = rule?.status ?? "disabled";
    ruleForm.confidence = Number.parseInt(rule?.confidence ?? "90", 10);
    ruleForm.description = rule?.description ?? "";
    ruleForm.positiveExample = rule?.positiveExample ?? "";
    ruleForm.falsePositiveGuidance = rule?.falsePositiveGuidance ?? "";
    ruleForm.enforcementMode = rule?.enforcementMode ?? "comment";
    ruleForm.detectorType = rule?.detectorType ?? "BUILTIN";
    ruleForm.matcherExpression = rule?.matcherExpression ?? "";
    ruleForm.exceptionPatterns = rule?.exceptionPatterns ?? "";
    savedForm.value = { ...ruleForm };
  };
  const mergeReceivedRule = (rule: ReviewRuleConfig, baseline: ReviewRuleConfigRequest) => {
    const edits = Object.fromEntries(ruleFields.filter(key => ruleForm[key] !== baseline[key]).map(key => [key, ruleForm[key]]));
    resetForm(rule); Object.assign(ruleForm, edits);
    editingRuleId.value = rule.id; editingPolicyVersion.value = rule.policyVersion;
  };

  const openEditDialog = (rule: ReviewRuleConfig) => {
    if (disposed || !canManage.value || !rulesCurrent.value) {
      return;
    }
    const latest = rules.value.find(row => row.id === rule.id);
    if (!latest) return;
    editorRevision += 1;
    editingRuleId.value = latest.id;
    editingPolicyVersion.value = latest.policyVersion;
    resetForm(latest);
    ruleSaveError.value = ""; ruleSaveNotice.value = ""; ruleRefreshError.value = "";
    dialogVisible.value = true;
  };

  const openCreateDialog = () => {
    if (disposed || !canManage.value) {
      return;
    }
    editorRevision += 1;
    editingRuleId.value = "";
    editingPolicyVersion.value = 0;
    resetForm();
    ruleSaveError.value = ""; ruleSaveNotice.value = ""; ruleRefreshError.value = "";
    dialogVisible.value = true;
  };

  const validateRuleForm = () => {
    if (!ruleForm.id.trim()) {
      return "请输入规则 ID";
    }
    if (!/^[A-Za-z0-9_-]+$/.test(ruleForm.id.trim())) {
      return "规则 ID 只能包含字母、数字、下划线和连字符";
    }
    if (!ruleForm.name.trim()) {
      return "请输入规则名称";
    }
    if (!ruleForm.scope.trim()) {
      return "请输入适用范围";
    }
    if (!ruleForm.description.trim()) {
      return "请输入规则说明";
    }
    if (!ruleForm.applicableLanguages.trim()) {
      return "请输入适用语言";
    }
    if (!ruleForm.filePatterns.trim()) {
      return "请输入文件匹配规则";
    }
    if (ruleForm.detectorType !== "BUILTIN" && !ruleForm.matcherExpression?.trim()) {
      return "声明式规则必须填写匹配表达式";
    }
    return "";
  };

  const normalizedPayload = (): ReviewRuleConfigRequest => ({
    id: ruleForm.id.trim().toUpperCase(),
    name: ruleForm.name.trim(),
    scope: ruleForm.scope.trim(),
    applicableLanguages: ruleForm.applicableLanguages.trim(),
    filePatterns: ruleForm.filePatterns.trim(),
    severity: ruleForm.severity,
    status: ruleForm.status,
    confidence: ruleForm.confidence,
    description: ruleForm.description.trim(),
    positiveExample: ruleForm.positiveExample.trim(),
    falsePositiveGuidance: ruleForm.falsePositiveGuidance.trim(),
    enforcementMode: ruleForm.enforcementMode,
    detectorType: ruleForm.detectorType,
    matcherExpression: ruleForm.matcherExpression,
    exceptionPatterns: ruleForm.exceptionPatterns
  });

  const readFreshRules = async () => {
    try { return await reloadRules() !== false && rulesCurrent.value; } catch { return false; }
  };

  const refreshEditingRule = async () => {
    if (!canRefreshRule.value) return;
    const id = editingRuleId.value || ruleForm.id.trim().toUpperCase();
    if (!id) return;
    const version = contextRevision; let editor = editorRevision; const baseline = { ...savedForm.value };
    const current = () => !disposed && canManage.value && version === contextRevision && editor === editorRevision && dialogVisible.value;
    refreshingRule.value = true; ruleRefreshError.value = "";
    try {
      if (!await readFreshRules()) {
        if (current()) ruleRefreshError.value = "最新规则读取失败，草稿已保留，请重试刷新。";
        return;
      }
      if (!current()) return;
      const latest = rules.value.find(rule => rule.id === id);
      if (!latest) { ruleRefreshError.value = "该规则当前不存在，草稿已保留，请核对规则 ID。"; return; }
      mergeReceivedRule(latest, baseline); editor = editorRevision;
      ruleSaveError.value = "";
      ruleSaveNotice.value = ruleHasUnsavedChanges.value ? "已读取最新版本并保留草稿，请核对后手动保存。" : "已读取最新规则版本。";
    } finally { refreshingRule.value = false; }
  };

  const saveRule = async () => {
    if (!canSaveRule.value) return;
    const validationMessage = validateRuleForm();
    if (validationMessage) {
      ElMessage.warning(validationMessage);
      return;
    }
    const id = editingRuleId.value; const payload = normalizedPayload(); const submitted = { ...ruleForm };
    const expectedPolicyVersion = editingPolicyVersion.value; const version = contextRevision; const context = contextIdentity();
    let editor = editorRevision;
    const contextCurrent = () => !disposed && canManage.value && version === contextRevision;
    const current = () => contextCurrent() && dialogVisible.value && editor === editorRevision;
    if (id && payload.id !== id) { ruleSaveError.value = "规则 ID 与编辑目标不一致，请重新打开编辑。"; return; }
    saving.value = true; ruleSaveError.value = ""; ruleSaveNotice.value = ""; ruleRefreshError.value = ""; cancelRulesRead();
    pendingRuleIds.value.add(payload.id);
    try {
      let saved: ReviewRuleConfig;
      try {
        saved = id ? await updateReviewRule(id, expectedPolicyVersion, payload) : await createReviewRule(payload);
      } catch (error) {
        if (!contextCurrent()) return;
        invalidateRules();
        if (current()) { ruleSaveError.value = getErrorMessage(error, "规则保存失败"); ElMessage.error(ruleSaveError.value); }
        await readFreshRules();
        return;
      }
      if (!contextCurrent()) return;
      invalidateRules();
      const accepted = saved?.id === payload.id && Number.isSafeInteger(saved.policyVersion) && saved.policyVersion > 0;
      if (current()) {
        if (accepted) {
          mergeReceivedRule(saved, submitted); editor = editorRevision;
          ruleSaveNotice.value = ruleHasUnsavedChanges.value ? "本次提交已保存，新的修改尚未保存。" : "本次提交已保存。";
          ElMessage.success(id ? "规则已更新" : "声明式规则已创建");
        } else {
          ruleSaveError.value = "保存请求已返回，但规则标识或版本不一致，请刷新确认。";
          ElMessage.warning(ruleSaveError.value);
        }
      }
      const refreshed = await readFreshRules();
      if (!current()) return;
      if (!refreshed) {
        ruleRefreshError.value = accepted ? "本次提交已保存，规则列表尚未刷新成功。" : "规则列表尚未刷新成功，请刷新确认。";
      } else if (accepted && !ruleHasUnsavedChanges.value) dialogVisible.value = false;
    } finally {
      saving.value = false;
      finishRuleOperation(payload.id, version, context);
    }
  };

  const toggleRule = async (rule: ReviewRuleConfig, value: string | number | boolean) => {
    if (!canChangeRule(rule.id) || (value !== "enabled" && value !== "disabled")) return;
    const latest = rules.value.find(row => row.id === rule.id)!;
    if (latest.policyVersion !== rule.policyVersion || latest.status !== rule.status || value === latest.status) return;
    const id = latest.id; const name = latest.name; const expectedPolicyVersion = latest.policyVersion;
    const nextStatus: RuleStatus = value; const version = contextRevision; const context = contextIdentity();
    const current = () => !disposed && canManage.value && version === contextRevision;
    pendingRuleIds.value.add(id); pendingStatusIds.value.add(id); delete ruleOperationErrors.value[id]; cancelRulesRead();
    try {
      const updated = await updateReviewRuleStatus(id, {
        status: nextStatus,
        expectedPolicyVersion
      });
      if (!current()) return;
      invalidateRules();
      const acknowledged = updated?.id === id && updated.status === nextStatus
        && Number.isSafeInteger(updated.policyVersion) && updated.policyVersion > 0;
      if (acknowledged) ElMessage.success(`${name} 已${nextStatus === "enabled" ? "启用" : "停用"}`);
      else { ruleOperationErrors.value[id] = "状态更新请求已返回，但规则标识、状态或版本不一致，请刷新确认。"; ElMessage.warning(ruleOperationErrors.value[id]); }
      const refreshed = await readFreshRules();
      if (current() && !refreshed) ElMessage.warning(acknowledged ? `${name} 状态已更新，规则列表尚未刷新成功` : "规则列表尚未刷新成功，请刷新确认");
    } catch (error) {
      if (current()) {
        invalidateRules(); ruleOperationErrors.value[id] = getErrorMessage(error, "规则状态更新失败");
        ElMessage.error(ruleOperationErrors.value[id]); await readFreshRules();
      }
    } finally {
      finishRuleOperation(id, version, context);
    }
  };

  return {
    dialogVisible,
    editingPolicyVersion,
    editingRuleId,
    ruleForm,
    saving,
    canSaveRule,
    ruleHasUnsavedChanges,
    ruleVersionChanged,
    ruleSaveError,
    ruleSaveNotice,
    ruleRefreshError,
    refreshingRule,
    statusSavingId,
    busyRuleIds,
    canChangeRule,
    canRefreshRule,
    ruleOperationErrors,
    openEditDialog,
    openCreateDialog,
    resetForm,
    saveRule,
    refreshEditingRule,
    toggleRule,
    validateRuleForm
  };
};
