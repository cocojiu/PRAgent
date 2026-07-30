export const assessmentStatusText = (status?: string) => {
  const labels: Record<string, string> = {
    complete: "评估完整",
    partial: "部分评估",
    failed: "评估失败",
    superseded: "评估过期"
  };
  return status ? labels[status.toLowerCase()] ?? status : "评估中";
};

export const assessmentStatusClass = (status?: string) => {
  const classes: Record<string, string> = {
    complete: "success",
    partial: "warning",
    failed: "danger",
    superseded: "info"
  };
  return status ? classes[status.toLowerCase()] ?? "pending" : "pending";
};

export const assessmentStatusDescription = (status?: string) => {
  const descriptions: Record<string, string> = {
    complete: "规则、模型和输入均完成评估，风险等级可用于决策。",
    partial: "仅完成部分评估，风险等级只反映已成功处理的输入。",
    failed: "本次评估未形成有效风险结论，不应按高风险处理。",
    superseded: "提交已变化，本次评估结果已过期。"
  };
  return status
    ? descriptions[status.toLowerCase()] ?? "当前任务尚未形成标准评估状态。"
    : "评估仍在进行中。";
};
