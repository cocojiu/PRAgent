export const commonUserMessages = {
  actionFailed: "操作失败",
  authFailed: "认证失败",
  badRequest: "请求参数不正确，请检查后重试",
  forbidden: "当前账号权限不足，无法执行该操作",
  internalError: "服务暂时不可用，请稍后重试",
  invalidResponse: "服务返回的数据格式异常，请稍后重试",
  networkError: "网络连接异常，请检查后重试",
  payloadTooLarge: "提交内容过大，请精简后重试",
  requestFailed: "请求失败",
  sessionExpired: "登录状态已失效，请重新登录",
  taskNotFound: "审查任务不存在或已被删除",
  tooManyRequests: "请求过于频繁，请稍后重试"
} as const;

export const integrationConfigLabels = ["GitHub", "MySQL", "RabbitMQ", "审查策略"] as const;

export const integrationConfigMessages = {
  loadFailed: (failed: readonly string[]) =>
    `以下配置加载失败：${failed.join("、")}。其他可用配置已加载，可点击重试。`,
  saveSucceeded: (succeeded: readonly string[]) =>
    `配置保存成功：${succeeded.join("、")}`,
  savePartiallyFailed: ({
    failed,
    succeeded,
    syncFailed
  }: {
    failed: readonly string[];
    succeeded: readonly string[];
    syncFailed: readonly string[];
  }) => {
    const succeededText = succeeded.length > 0 ? succeeded.join("、") : "无";
    const syncText = syncFailed.length > 0
      ? `服务端状态重新同步失败：${syncFailed.join("、")}。`
      : "服务端状态已重新加载。";
    return `配置部分保存失败。成功：${succeededText}。失败：${failed.join("、")}。${syncText}`;
  }
} as const;

export const reviewDetailMessages = {
  invalidTaskId: "审查任务 ID 无效",
  pollFailed: (message: string) => `自动刷新失败：${message}`,
  pollingPaused: (failureCount: number) =>
    `自动刷新连续失败 ${failureCount} 次，已暂停。请手动刷新。`
} as const;
