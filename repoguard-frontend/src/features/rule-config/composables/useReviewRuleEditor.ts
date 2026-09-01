import { reactive, ref, type Ref } from "vue";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import { createReviewRule, updateReviewRule, updateReviewRuleStatus } from "@/api/config";
import type {
  ReviewRuleConfig,
  ReviewRuleConfigRequest,
  RuleStatus
} from "@/types";
import { getErrorMessage } from "@/utils/errors";

type ReviewRuleEditorOptions = {
  canManage: Readonly<Ref<boolean>>;
  reloadRules: () => Promise<void>;
  rules: Ref<ReviewRuleConfig[]>;
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

export const useReviewRuleEditor = ({ canManage, reloadRules, rules }: ReviewRuleEditorOptions) => {
  const saving = ref(false);
  const statusSavingId = ref("");
  const dialogVisible = ref(false);
  const editingRuleId = ref("");
  const editingPolicyVersion = ref(0);
  const ruleForm = reactive<ReviewRuleConfigRequest>(createEmptyRuleForm());

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
  };

  const openEditDialog = (rule: ReviewRuleConfig) => {
    if (!canManage.value) {
      return;
    }
    editingRuleId.value = rule.id;
    editingPolicyVersion.value = rule.policyVersion;
    resetForm(rule);
    dialogVisible.value = true;
  };

  const openCreateDialog = () => {
    if (!canManage.value) {
      return;
    }
    editingRuleId.value = "";
    editingPolicyVersion.value = 0;
    resetForm();
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

  const saveRule = async () => {
    if (!canManage.value) {
      return;
    }
    const validationMessage = validateRuleForm();
    if (validationMessage) {
      ElMessage.warning(validationMessage);
      return;
    }
    saving.value = true;
    try {
      if (editingRuleId.value) {
        await updateReviewRule(editingRuleId.value, editingPolicyVersion.value, normalizedPayload());
        ElMessage.success("规则已更新");
      } else {
        await createReviewRule(normalizedPayload());
        ElMessage.success("声明式规则已创建");
      }
      dialogVisible.value = false;
      await reloadRules();
    } catch (error) {
      ElMessage.error(getErrorMessage(error, "规则操作失败"));
      await reloadRules();
      const refreshedRule = rules.value.find(rule => rule.id === editingRuleId.value);
      if (refreshedRule) {
        editingPolicyVersion.value = refreshedRule.policyVersion;
        resetForm(refreshedRule);
      }
    } finally {
      saving.value = false;
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
    statusSavingId,
    openEditDialog,
    openCreateDialog,
    resetForm,
    saveRule,
    toggleRule,
    validateRuleForm
  };
};
