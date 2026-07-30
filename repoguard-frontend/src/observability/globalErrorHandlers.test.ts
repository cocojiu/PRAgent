import { describe, expect, it, vi } from "vitest";
import { createApp } from "vue";
import { RequestError } from "@/utils/errors";
import { installGlobalErrorHandlers } from "./globalErrorHandlers";

const TestComponent = { template: "<div />" };

describe("global frontend error handlers", () => {
  it("reports Vue, window, and unhandled promise errors through one fallback", () => {
    const app = createApp(TestComponent);
    const reporter = vi.fn();
    const uninstall = installGlobalErrorHandlers(app, reporter);

    app.config.errorHandler?.(new Error("vue failed"), null, "render");
    window.dispatchEvent(new ErrorEvent("error", { error: new Error("window failed") }));
    window.dispatchEvent(new PromiseRejectionEvent("unhandledrejection", {
      promise: Promise.resolve(),
      reason: new Error("promise failed")
    }));

    expect(reporter).toHaveBeenCalledTimes(3);
    expect(reporter.mock.calls.map(([, source]) => source)).toEqual([
      "vue:render",
      "window:error",
      "window:unhandledrejection"
    ]);
    uninstall();
  });

  it("ignores caller-initiated request cancellation", () => {
    const app = createApp(TestComponent);
    const reporter = vi.fn();
    const uninstall = installGlobalErrorHandlers(app, reporter);

    app.config.errorHandler?.(
      new RequestError("aborted", { status: 0, code: "REQUEST_ABORTED" }),
      null,
      "setup"
    );

    expect(reporter).not.toHaveBeenCalled();
    uninstall();
  });
});
