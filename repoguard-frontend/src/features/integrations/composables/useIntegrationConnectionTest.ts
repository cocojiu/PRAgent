import { ElMessage } from "element-plus/es/components/message/index.mjs";
import type { ConnectionTestResult } from "@/types";
import { getErrorMessage } from "@/utils/errors";

type UseIntegrationConnectionTestOptions = {
  applyConnectionTestResult: (id: string, result: ConnectionTestResult) => void;
  hasIntegration: (id: string) => boolean;
  testActions: Record<string, () => Promise<ConnectionTestResult>>;
  testingConnections: Record<string, boolean>;
};

export const useIntegrationConnectionTest = ({
  applyConnectionTestResult,
  hasIntegration,
  testActions,
  testingConnections
}: UseIntegrationConnectionTestOptions) => {
  const testConnection = async (id: string) => {
    const action = testActions[id];
    if (!action || !hasIntegration(id)) {
      ElMessage.warning("Connection test is not available");
      return;
    }
    if (testingConnections[id]) {
      return;
    }
    testingConnections[id] = true;
    try {
      const result = await action();
      applyConnectionTestResult(id, result);
      if (result.success) {
        ElMessage.success(result.message);
      } else {
        ElMessage.error(result.message);
      }
    } catch (error) {
      const message = getErrorMessage(error, "Connection test failed");
      applyConnectionTestResult(id, {
        success: false,
        status: "failed",
        message,
        checkedAt: new Date().toLocaleString()
      });
      ElMessage.error(message);
    } finally {
      testingConnections[id] = false;
    }
  };

  return {
    testConnection
  };
};
