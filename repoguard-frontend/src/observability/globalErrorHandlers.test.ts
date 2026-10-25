import { afterEach, describe, expect, it, vi } from "vitest";
import { createApp } from "vue";
import { RequestError } from "@/utils/errors";
import { installGlobalErrorHandlers, type GlobalErrorReporter } from "./globalErrorHandlers";

const message = "ResizeObserver loop completed with undelivered notifications.";
const messages = vi.hoisted(() => ({ error: vi.fn() }));
vi.mock("element-plus/es/components/message/index.mjs", () => ({ ElMessage: messages }));
const TestComponent = { template: "<div />" };
const cleanups: Array<() => void> = [];

afterEach(() => {
  cleanups.splice(0).forEach((uninstall) => uninstall());
  vi.restoreAllMocks();
  vi.clearAllMocks();
});

const installForBrowserEvent = (reporter?: GlobalErrorReporter) => {
  const registration = vi.spyOn(window, "addEventListener");
  cleanups.push(installGlobalErrorHandlers(createApp(TestComponent), reporter));
  const callback = registration.mock.calls.find(([type]) => type === "error")?.[1] as
    (event: ErrorEvent) => void;
  registration.mockRestore();
  return callback;
};

const browserNotice = (overrides: Partial<ErrorEvent> = {}) => ({
  isTrusted: true, message, error: null, lineno: 0, colno: 0, ...overrides
} as ErrorEvent);

describe("global frontend error handlers", () => {
  it("reports Vue, window, and unhandled promise errors through one fallback", () => {
    const app = createApp(TestComponent);
    const reporter = vi.fn();
    const uninstall = installGlobalErrorHandlers(app, reporter);
    cleanups.push(uninstall);

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
    cleanups.push(uninstall);

    app.config.errorHandler?.(
      new RequestError("aborted", { status: 0, code: "REQUEST_ABORTED" }),
      null,
      "setup"
    );

    expect(reporter).not.toHaveBeenCalled();
    uninstall();
  });

  it("keeps native deferred resize notifications visible to the reporter", () => {
    const reporter = vi.fn();
    installForBrowserEvent(reporter)(browserNotice());
    expect(reporter).toHaveBeenCalledWith(message, "window:resize-observer");
  });

  it.each<[string, Partial<ErrorEvent>]>([
    ["synthetic event", { isTrusted: false }],
    ["real Error with the same message", { error: new Error(message) }],
    ["thrown string", { error: message }],
    ["different browser notification", { message: "Other browser failure" }],
    ["script line", { lineno: 5 }],
    ["script column", { colno: 3 }]
  ])("retains the error source for %s", (_name, fields) => {
    const reporter = vi.fn();
    const event = browserNotice(fields);
    installForBrowserEvent(reporter)(event);
    expect(reporter).toHaveBeenCalledWith(event.error ?? event.message, "window:error");
  });

  it("warns without a fatal toast for native delivery, while real errors still use the fallback", () => {
    const warning = vi.spyOn(console, "warn").mockImplementation(() => {});
    const error = vi.spyOn(console, "error").mockImplementation(() => {});
    vi.spyOn(Date, "now").mockReturnValue(10_000);
    const callback = installForBrowserEvent();
    callback(browserNotice());
    expect(warning).toHaveBeenCalledWith(expect.stringContaining("ResizeObserver"), message);
    expect(error).not.toHaveBeenCalled();
    expect(messages.error).not.toHaveBeenCalled();

    callback(browserNotice({ error: new Error(message) }));
    expect(error).toHaveBeenCalledOnce();
    expect(messages.error).toHaveBeenCalledOnce();
  });

  it("does not reclassify Vue or promise failures that contain the same text", () => {
    const app = createApp(TestComponent);
    const reporter = vi.fn();
    cleanups.push(installGlobalErrorHandlers(app, reporter));
    app.config.errorHandler?.(new Error(message), null, "render");
    window.dispatchEvent(new PromiseRejectionEvent("unhandledrejection", {
      promise: Promise.resolve(), reason: new Error(message)
    }));
    expect(reporter.mock.calls.map(([, source]) => source)).toEqual([
      "vue:render", "window:unhandledrejection"
    ]);
  });
});
