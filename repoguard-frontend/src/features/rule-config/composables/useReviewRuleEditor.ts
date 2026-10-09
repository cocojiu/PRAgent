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
  const statusSavingId = ref("");
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
  };
  watch([canManage, activeTenant, () => currentUser.value?.id], () => { contextRevision += 1; clearEditor(); }, { flush: "sync" });
  if (getCurrentScope()) onScopeDispose(() => { disposed = true; contextRevision += 1; clearEditor(); });
  const canSaveRule = computed(() => !disposed && canManage.value && rulesCurrent.value && dialogVisible.value
    && !saving.value && !refreshingRule.value && !ruleVersionChanged.value && ruleHasUnsavedChanges.value);

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
    if (disposed || !canManage.value || !dialogVisible.value || saving.value || refreshingRule.value) return;
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
      if (!disposed && version !== contextRevision && context === contextIdentity()) invalidateRules();
    }
  };

  const toggleRule = async (rule: ReviewRuleConfig, value: string | number | boolean) => {
    if (!canManage.value || statusSavingId.value) {
      rule.status = value === "enabled" ? "disabled" : "enabled";
      return;
    }
    const nextStatus = value === "enabled" ? "enabled" : "disabled";
    const previousStatus: RuleStatus = nextStatus === "enabled" ? "disabled" : "enabled";
    statusSavingId.value = rule.id;
    try {
      const updated = await updateReviewRuleStatus(rule.id, {
        status: nextStatus,
        expectedPolicyVersion: rule.policyVersion
      });
      Object.assign(rule, updated);
      ElMessage.success(`${rule.name} 已${nextStatus === "enabled" ? "启用" : "停用"}`);
      await reloadRules();
    } catch (error) {
      rule.status = previousStatus;
      ElMessage.error(getErrorMessage(error, "规则操作失败"));
      await reloadRules();
    } finally {
      statusSavingId.value = "";
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
    openEditDialog,
    openCreateDialog,
    resetForm,
    saveRule,
    refreshEditingRule,
    toggleRule,
    validateRuleForm
  };
};
