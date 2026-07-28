import type { App } from "vue";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import { RequestError } from "@/utils/errors";
import { commonUserMessages } from "@/utils/userMessages";

export type GlobalErrorReporter = (error: unknown, source: string) => void;

export const installGlobalErrorHandlers = (
  app: App,
  reporter: GlobalErrorReporter = defaultReporter
) => {
  const report = (error: unknown, source: string) => {
    if (!isExpectedCancellation(error)) {
      reporter(error, source);
    }
  };
  app.config.errorHandler = (error, _instance, info) => {
    report(error, `vue:${info}`);
  };
  const onWindowError = (event: ErrorEvent) => {
    report(event.error ?? event.message, "window:error");
  };
  const onUnhandledRejection = (event: PromiseRejectionEvent) => {
    if (isExpectedCancellation(event.reason)) {
      return;
    }
    event.preventDefault();
    report(event.reason, "window:unhandledrejection");
  };
  window.addEventListener("error", onWindowError);
  window.addEventListener("unhandledrejection", onUnhandledRejection);

  return () => {
    window.removeEventListener("error", onWindowError);
    window.removeEventListener("unhandledrejection", onUnhandledRejection);
  };
};

export const isExpectedCancellation = (error: unknown) =>
  error instanceof RequestError && error.code === "REQUEST_ABORTED";

let lastFallbackMessageAt = 0;

const defaultReporter: GlobalErrorReporter = (error, source) => {
  console.error(`[RepoGuard] Unhandled frontend error (${source})`, error);
  const now = Date.now();
  if (now - lastFallbackMessageAt >= 1_000) {
    lastFallbackMessageAt = now;
    ElMessage.error(commonUserMessages.unexpectedError);
  }
};
