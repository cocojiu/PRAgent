import { ElMessage } from "element-plus/es/components/message/index.mjs";
import { getCurrentScope, onScopeDispose, shallowReactive, watch, type Ref } from "vue";
import type { ConnectionTestResult, IntegrationConfig } from "@/types";
import { activeTenant } from "@/stores/tenantContext";
import { getErrorMessage } from "@/utils/errors";
import { formatDateTime } from "@/utils/dateTime";

type UseIntegrationConnectionTestOptions = {
  canManage: Ref<boolean>;
  captureConfig: (id: string) => Readonly<Record<string, unknown>>;
  applyConnectionTestResult: (id: string, result: ConnectionTestResult) => void;
  hasIntegration: (id: string) => boolean;
  testActions: Record<string, () => Promise<ConnectionTestResult>>;
  testingConnections: Record<string, boolean>;
};

export const useIntegrationConnectionTest = ({
  canManage,
  captureConfig,
  applyConnectionTestResult,
  hasIntegration,
  testActions,
  testingConnections
}: UseIntegrationConnectionTestOptions) => {
  type TestContext = { config: Readonly<Record<string, unknown>>; revision: number };
  const contexts = shallowReactive<Record<string, TestContext>>({});
  const invalidated = shallowReactive(new Set<string>());
  let disposed = false;
  let revision = 0;
  const invalidate = () => {
    revision += 1;
    for (const id of Object.keys(contexts)) { invalidated.add(id); delete contexts[id]; }
  };
  watch([() => canManage.value, () => activeTenant.value], invalidate, { flush: "sync" });
  if (getCurrentScope()) onScopeDispose(() => { disposed = true; invalidate(); });

  const matches = (id: string, context: TestContext) => {
    if (disposed || !canManage.value || context.revision !== revision) return false;
    try {
      const current = captureConfig(id);
      const keys = Object.keys(context.config);
      return keys.length === Object.keys(current).length && keys.every((key) => Object.is(context.config[key], current[key]));
    } catch { return false; }
  };

  const displayConnection = (item: IntegrationConfig, hasUnsavedChanges = false): IntegrationConfig => {
    const context = contexts[item.id];
    const stale = invalidated.has(item.id) || (context ? !matches(item.id, context) : hasUnsavedChanges);
    if (!stale && !testingConnections[item.id]) return item;
    return {
      ...item,
      status: "pending",
      statusText: stale ? "待重新测试" : "检测中",
      message: stale ? "配置已改变，请重新测试连接；上次结果不能确认当前配置。" : "正在检测当前配置，上次结果仅供参考。",
      metaLabel: "上次检测时间",
      diagnostics: undefined
    };
  };

  const testConnection = async (id: string) => {
    if (disposed || !canManage.value) return;
    const action = testActions[id];
    if (!action || !hasIntegration(id)) {
      ElMessage.warning("Connection test is not available");
      return;
    }
    if (testingConnections[id]) {
      return;
    }
    let context: TestContext;
    try { context = { config: { ...captureConfig(id) }, revision }; }
    catch (error) { ElMessage.error(getErrorMessage(error, "连接测试配置无效")); return; }
    contexts[id] = context;
    invalidated.delete(id);
    const current = () => contexts[id] === context && matches(id, context);
    testingConnections[id] = true;
    try {
      const result = await action();
      if (!current()) { if (!disposed) invalidated.add(id); return; }
      applyConnectionTestResult(id, result);
      if (result.success) {
        ElMessage.success(result.message);
      } else {
        ElMessage.error(result.message);
      }
    } catch (error) {
      if (!current()) { if (!disposed) invalidated.add(id); return; }
      const message = getErrorMessage(error, "Connection test failed");
      applyConnectionTestResult(id, {
        success: false,
        status: "failed",
        message,
        checkedAt: formatDateTime(new Date())
      });
      ElMessage.error(message);
    } finally {
      if (!disposed) testingConnections[id] = false;
    }
  };

  return {
    testConnection,
    displayConnection
  };
};
