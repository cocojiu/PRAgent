import { createApp, defineComponent, h, nextTick, reactive, type App } from "vue";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { setActiveTenant } from "@/stores/tenantContext";
import type { ReviewAssignmentOptions } from "@/types";

/* eslint-disable vue/one-component-per-file -- Rendering stubs isolate component behavior. */
const api = vi.hoisted(() => ({ fetch: vi.fn(), confirm: vi.fn() }));
vi.mock("@/api/reviewAssignment", () => ({ fetchReviewAssignmentOptions: api.fetch, confirmReviewMemberAssignment: api.confirm }));
import Card from "./ReviewMemberAssignmentCard.vue";
let app: App | null;
let host: HTMLDivElement;
let props: { taskId: number; headSha: string };
const onAssigned = vi.fn();
const head = "a".repeat(40), version = "b".repeat(64);
const response = (): ReviewAssignmentOptions => ({ taskId: 9, attemptId: 12, headSha: head, assignmentVersion: version,
  members: [{ userId: 11, username: "reviewer" }, { userId: 22, username: "other-reviewer" }], hasMore: false });
const flush = async () => { await Promise.resolve(); await nextTick(); await Promise.resolve(); await nextTick(); };
const load = async () => { host.querySelector("form")!.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true })); await flush(); };
const select = async () => { host.querySelector<HTMLInputElement>('input[type="radio"][value="22"]')!.click(); await flush(); };
const confirm = async () => { [...host.querySelectorAll<HTMLButtonElement>("button")].find(button => button.textContent === "确认分派")!.click(); await flush(); };
beforeEach(async () => {
  vi.resetAllMocks(); setActiveTenant("tenant-one"); api.fetch.mockResolvedValue(response());
  props = reactive({ taskId: 9, headSha: head }); host = document.createElement("div"); document.body.append(host);
  app = createApp({ render: () => h(Card, { ...props, onAssigned }) });
  app.component("ElButton", defineComponent({ props: { loading: Boolean, disabled: Boolean, nativeType: { type: String, default: "button" } },
    setup: (buttonProps, { slots }) => () => h("button", { type: buttonProps.nativeType || "button",
      disabled: buttonProps.disabled || buttonProps.loading }, slots.default?.()) }));
  app.component("ElAlert", defineComponent({ props: { title: { type: String, default: "" } },
    setup: alert => () => h("div", { role: "alert" }, alert.title) }));
  app.mount(host); await flush();
});
afterEach(() => { app?.unmount(); app = null; host.remove(); setActiveTenant(); });
it("queries only on request and requires an explicit member choice before assignment", async () => {
  expect(api.fetch).not.toHaveBeenCalled(); await load();
  expect(api.fetch).toHaveBeenCalledWith(9, undefined, { signal: expect.any(AbortSignal) });
  expect(host.textContent).toContain("other-reviewer"); expect(host.querySelector<HTMLInputElement>('input[type="radio"]')!.checked).toBe(false);
  expect([...host.querySelectorAll<HTMLButtonElement>("button")].find(button => button.textContent === "确认分派")!.disabled).toBe(true);
  expect(api.confirm).not.toHaveBeenCalled();
});
it("binds confirmation to the selected user and current attempt, head and assignment version", async () => {
  await load(); await select(); api.confirm.mockResolvedValue({ taskId: 9, attemptId: 12, headSha: head, assignee: "canonical-member" }); await confirm();
  expect(api.confirm).toHaveBeenCalledExactlyOnceWith(9, { userId: 22, attemptId: 12, headSha: head, assignmentVersion: version }, { signal: expect.any(AbortSignal) });
  expect(host.textContent).toContain("已分派给 canonical-member"); expect(host.querySelector("fieldset")).toBeNull();
  expect(onAssigned).toHaveBeenCalledExactlyOnceWith("canonical-member");
});
it("clears the old choice when the search changes and uses a literal username prefix", async () => {
  await load(); await select(); const input = host.querySelector<HTMLInputElement>('input:not([type])')!;
  input.value = " member_% "; input.dispatchEvent(new Event("input", { bubbles: true })); await flush();
  expect(host.querySelector("fieldset")).toBeNull(); await load();
  expect(api.fetch).toHaveBeenLastCalledWith(9, "member_%", { signal: expect.any(AbortSignal) });
});
it("shows empty and truncated search results without making automatic assignments", async () => {
  api.fetch.mockResolvedValueOnce({ ...response(), members: [], hasMore: false }); await load();
  expect(host.textContent).toContain("没有匹配的有效复核成员"); expect(host.querySelector("fieldset")).toBeNull();
  api.fetch.mockResolvedValueOnce({ ...response(), hasMore: true }); await load(); expect(host.textContent).toContain("仅显示前 20 位成员");
  expect(api.confirm).not.toHaveBeenCalled();
});
it("renders account names as text", async () => {
  api.fetch.mockResolvedValueOnce({ ...response(), members: [{ userId: 22, username: '<img src=x onerror="alert(1)">' }] });
  await load(); expect(host.querySelector("img")).toBeNull(); expect(host.textContent).toContain("<img src=x");
});
it("clears stale members and hides private details after a read failure", async () => {
  await load(); api.fetch.mockRejectedValueOnce(new Error("private-server-details")); await load();
  expect(host.querySelector("fieldset")).toBeNull(); expect(host.textContent).toContain("成员查询失败");
  expect(host.textContent).not.toContain("private-server-details");
});
it.each(["task", "head", "tenant"])("aborts and ignores a late read when %s changes", async change => {
  let finish!: (data: ReviewAssignmentOptions) => void; api.fetch.mockReturnValueOnce(new Promise(resolve => { finish = resolve; }));
  await load(); const signal = api.fetch.mock.calls[0]![2].signal as AbortSignal;
  if (change === "task") props.taskId = 10;
  if (change === "head") props.headSha = "c".repeat(40);
  if (change === "tenant") setActiveTenant("tenant-two");
  await flush(); expect(signal.aborted).toBe(true); finish(response()); await flush(); expect(host.querySelector("fieldset")).toBeNull();
});
it("aborts an unmounted component and ignores its response", async () => {
  let finish!: (data: ReviewAssignmentOptions) => void; api.fetch.mockReturnValueOnce(new Promise(resolve => { finish = resolve; }));
  await load(); const signal = api.fetch.mock.calls[0]![2].signal as AbortSignal; app!.unmount(); app = null;
  expect(signal.aborted).toBe(true); finish(response()); await flush(); expect(host.textContent).toBe("");
});
it("serializes duplicate writes and makes a stale or uncertain response require refresh", async () => {
  await load(); await select(); let fail!: (error: Error) => void;
  api.confirm.mockReturnValueOnce(new Promise((_resolve, reject) => { fail = reject; }));
  await confirm(); await confirm(); expect(api.confirm).toHaveBeenCalledTimes(1);
  fail(new Error("private-conflict-details")); await flush(); expect(host.textContent).toContain("分派结果未确认");
  expect(host.textContent).not.toContain("private-conflict-details"); expect(host.querySelector("fieldset")).toBeNull();
});
it.each(["task", "head", "tenant"])("ignores a late successful write when %s changes", async change => {
  await load(); await select(); let finish!: (data: unknown) => void; api.confirm.mockReturnValueOnce(new Promise(resolve => { finish = resolve; }));
  await confirm(); const signal = api.confirm.mock.calls[0]![2].signal as AbortSignal;
  if (change === "task") props.taskId = 10;
  if (change === "head") props.headSha = "c".repeat(40);
  if (change === "tenant") setActiveTenant("tenant-two");
  await flush(); expect(signal.aborted).toBe(true); finish({ taskId: 9, attemptId: 12, headSha: head, assignee: "reviewer" }); await flush();
  expect(onAssigned).not.toHaveBeenCalled(); expect(host.textContent).not.toContain("已分派给");
});
it.each([{ taskId: 10 }, { headSha: "c".repeat(40) }])("rejects mismatched read identity %j", async changed => {
  api.fetch.mockResolvedValueOnce({ ...response(), ...changed }); await load();
  expect(host.querySelector("fieldset")).toBeNull(); expect(host.textContent).toContain("成员查询失败");
});
it("rejects a mismatched confirmation and does not claim success", async () => {
  await load(); await select(); api.confirm.mockResolvedValueOnce({ taskId: 9, attemptId: 13, headSha: head, assignee: "reviewer" });
  await confirm(); expect(host.textContent).toContain("分派结果未确认"); expect(onAssigned).not.toHaveBeenCalled();
});
it("rejects incomplete task identity without making a request", async () => {
  props.headSha = "short"; await flush(); await load(); expect(api.fetch).not.toHaveBeenCalled(); expect(host.textContent).toContain("缺少当前任务或提交标识");
});
