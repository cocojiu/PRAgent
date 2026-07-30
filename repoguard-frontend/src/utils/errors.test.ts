import { describe, expect, it } from "vitest";
import { getAuthErrorMessage, getErrorMessage, RequestError } from "./errors";

describe("user-facing error messages", () => {
  it.each([
    ["BAD_REQUEST", "请求参数不正确，请检查后重试"],
    ["PAYLOAD_TOO_LARGE", "提交内容过大，请精简后重试"],
    ["TOO_MANY_REQUESTS", "请求过于频繁，请稍后重试"],
    ["TASK_NOT_FOUND", "审查任务不存在或已被删除"],
    ["CONFLICT", "状态已变化，请刷新"],
    ["INTERNAL_ERROR", "服务暂时不可用，请稍后重试"],
    ["INVALID_API_RESPONSE", "服务返回的数据格式异常，请稍后重试"]
  ])("maps backend code %s to stable copy", (code, expected) => {
    expect(getErrorMessage(new RequestError("internal implementation detail", { code }))).toBe(expected);
  });

  it("does not expose unknown backend messages", () => {
    const error = new RequestError("jdbc:mysql://db.internal:3306/secret", { code: "UNKNOWN" });

    expect(getErrorMessage(error, "数据加载失败")).toBe("数据加载失败");
    expect(getAuthErrorMessage(error, "登录失败")).toBe("登录失败");
  });

  it("preserves safe backend business messages for actionable client errors", () => {
    const error = new RequestError("任务状态已变化，请刷新后重试", {
      status: 409,
      code: "CONFLICT"
    });

    expect(getErrorMessage(error)).toBe("任务状态已变化，请刷新后重试");
  });

  it("distinguishes request timeouts from network failures and caller cancellation", () => {
    expect(getErrorMessage(new RequestError("timeout", {
      status: 0,
      code: "REQUEST_TIMEOUT"
    }))).toBe("请求超时，请稍后重试");
    expect(getErrorMessage(new RequestError("aborted", {
      status: 0,
      code: "REQUEST_ABORTED"
    }))).toBe("请求已取消");
  });

  it("keeps local validation errors readable", () => {
    expect(getErrorMessage(new Error("表单内容不完整"))).toBe("表单内容不完整");
  });
});
