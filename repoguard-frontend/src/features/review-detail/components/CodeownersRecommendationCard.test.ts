import { createApp, defineComponent, h, nextTick, reactive, type App } from "vue";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { setActiveTenant } from "@/stores/tenantContext";
import type { CodeownersRecommendations } from "@/types";

/* eslint-disable vue/one-component-per-file -- Lightweight rendering stubs isolate this component. */
const api = vi.hoisted(() => ({ fetch: vi.fn(), accept: vi.fn() }));
vi.mock("@/api/codeowners", () => ({ fetchCodeownersRecommendations: api.fetch, acceptCodeownersRecommendation: api.accept }));
import Card from "./CodeownersRecommendationCard.vue";
const onAssigned = vi.fn();
let app: App | null;
let host: HTMLDivElement;
let props: { taskId: number; headSha: string };
const head = "a".repeat(40), base = "b".repeat(40);
const response = (): CodeownersRecommendations => ({ status: "RECOMMENDATIONS", taskId: 9, attemptId: 12,
  headSha: head, baseSha: base, sourcePath: ".github/CODEOWNERS",
  candidates: [{ userId: 11, username: "reviewer", coveredFiles: 1, findingCount: 2, riskScore: 4,
    externalIdentities: ["@team/backend"], paths: ["src/A.java"] }],
  basis: [{ path: "src/A.java", pattern: "src/**", line: 3, owners: ["@team/backend"], changedFiles: ["src/A.java"] },
    { path: "docs/help.txt", pattern: null, line: 0, owners: [], changedFiles: ["docs/help.txt"] }],
  uncoveredPaths: ["docs/help.txt"], unmappedIdentities: ["@inactive"] });
const flush = async () => { await Promise.resolve(); await nextTick(); await Promise.resolve(); await nextTick(); };
const click = async () => { host.querySelector<HTMLButtonElement>("button")!.click(); await flush(); };
const pending = () => {
  let finish!: (value: CodeownersRecommendations) => void;
  api.fetch.mockReturnValueOnce(new Promise(resolve => { finish = resolve; }));
  return (value = response()) => finish(value);
};
beforeEach(async () => {
  vi.resetAllMocks(); setActiveTenant("tenant-one"); api.fetch.mockResolvedValue(response());
  props = reactive({ taskId: 9, headSha: head });
  host = document.createElement("div"); document.body.append(host);
  app = createApp({ render: () => h(Card, { ...props, onAssigned }) });
  app.component("ElButton", defineComponent({ props: { loading: Boolean, disabled: Boolean },
    setup: (buttonProps, { slots }) => () => h("button", { disabled: buttonProps.loading || buttonProps.disabled }, slots.default?.()) }));
  app.component("ElAlert", defineComponent({ props: { title: { type: String, default: "" } },
    setup: alertProps => () => h("div", { role: "alert" }, alertProps.title) }));
  app.mount(host); await flush();
});
afterEach(() => { app?.unmount(); app = null; host.remove(); setActiveTenant(); });

it("only queries on user request and displays the candidates, base evidence and uncovered paths", async () => {
  expect(api.fetch).not.toHaveBeenCalled(); await click();
  expect(api.fetch).toHaveBeenCalledWith(9, { signal: expect.any(AbortSignal) });
  expect(host.textContent).toContain("reviewer"); expect(host.textContent).toContain("覆盖 1 个文件，关联 2 条 Finding");
  expect(host.textContent).toContain(".github/CODEOWNERS"); expect(host.textContent).toContain(base.slice(0, 12));
  expect(host.textContent).toContain("第 3 行 src/**"); expect(host.textContent).toContain("docs/help.txt");
  expect(host.textContent).toContain("@inactive"); expect(host.textContent).toContain("推荐不会自动分派");
});
it("renders untrusted candidate names and paths as text", async () => {
  const data = response(); data.candidates[0]!.username = '<img src=x onerror="alert(1)">';
  data.uncoveredPaths = ["<script>alert(1)</script>"]; api.fetch.mockResolvedValueOnce(data); await click();
  expect(host.textContent).toContain(data.candidates[0]!.username);
  expect(host.querySelector("img,script")).toBeNull();
});
it("shows disabled status without candidate evidence", async () => {
  api.fetch.mockResolvedValueOnce({ ...response(), status: "DISABLED", candidates: [], basis: [], uncoveredPaths: [], unmappedIdentities: [] });
  await click(); expect(host.textContent).toContain("推荐尚未启用"); expect(host.textContent).not.toContain("reviewer");
});
it("clears a prior recommendation on refresh failure without exposing exception details", async () => {
  await click(); api.fetch.mockRejectedValueOnce(new Error("private-server-details")); await click();
  expect(host.textContent).toContain("推荐查询失败"); expect(host.textContent).not.toContain("reviewer");
  expect(host.textContent).not.toContain("private-server-details");
});
it.each(["task", "head", "tenant"])("aborts and ignores a late response when the %s changes", async change => {
  const finish = pending(); await click(); const signal = api.fetch.mock.calls[0]![1].signal as AbortSignal;
  if (change === "task") props.taskId = 10;
  if (change === "head") props.headSha = "c".repeat(40);
  if (change === "tenant") setActiveTenant("tenant-two");
  await flush(); expect(signal.aborted).toBe(true); finish(); await flush();
  expect(host.textContent).not.toContain("reviewer"); expect(host.querySelector("button")!.disabled).toBe(false);
});
it("does not let an old task request replace a newer task result", async () => {
  const finish = pending(); await click(); props.taskId = 10; await flush();
  api.fetch.mockResolvedValueOnce({ ...response(), taskId: 10, candidates: [{ ...response().candidates[0]!, username: "new-reviewer" }] });
  await click(); finish(); await flush(); expect(host.textContent).toContain("new-reviewer");
  expect(host.querySelector("strong")?.textContent).toBe("new-reviewer");
  expect(api.fetch).toHaveBeenCalledTimes(2);
});
it("aborts on unmount and ignores later results", async () => {
  const finish = pending(); await click(); const signal = api.fetch.mock.calls[0]![1].signal as AbortSignal;
  app!.unmount(); app = null; expect(signal.aborted).toBe(true); finish(); await flush(); expect(host.textContent).toBe("");
});
it("serializes repeated clicks while a request is in flight", async () => {
  const finish = pending(); await click(); await click(); expect(api.fetch).toHaveBeenCalledTimes(1);
  finish(); await flush(); await click(); expect(api.fetch).toHaveBeenCalledTimes(2);
});
it.each([
  { taskId: 10 }, { headSha: "c".repeat(40) }, { baseSha: null }, { attemptId: null }
])("rejects recommendations with mismatched or incomplete version metadata %j", async invalid => {
  api.fetch.mockResolvedValueOnce({ ...response(), ...invalid }); await click();
  expect(host.textContent).toContain("推荐结果与当前提交不匹配"); expect(host.textContent).not.toContain("reviewer");
});
it("rejects an incomplete task identity without calling the API", async () => {
  props.headSha = "short"; await flush(); await click(); expect(api.fetch).not.toHaveBeenCalled();
  expect(host.textContent).toContain("缺少当前任务或提交标识");
});

const accept = async () => {
  [...host.querySelectorAll<HTMLButtonElement>("button")].find(button => button.textContent === "接受推荐并分派")!.click(); await flush();
};
it("accepts only after an explicit click and sends the selected server identity and version", async () => {
  await click(); expect(api.accept).not.toHaveBeenCalled();
  api.accept.mockResolvedValueOnce({ taskId: 9, attemptId: 12, headSha: head, assignee: "reviewer" }); await accept();
  expect(api.accept).toHaveBeenCalledWith(9, { userId: 11, attemptId: 12, headSha: head, baseSha: base }, { signal: expect.any(AbortSignal) });
  expect(host.textContent).toContain("已分派给 reviewer"); expect(host.querySelector("ol")).toBeNull();
  expect(onAssigned).toHaveBeenCalledExactlyOnceWith("reviewer");
});
it("clears recommendations when assignment is rejected and asks the user to verify the outcome", async () => {
  await click(); api.accept.mockRejectedValueOnce(new Error("private-details")); await accept();
  expect(host.textContent).toContain("分派结果未确认"); expect(host.textContent).not.toContain("private-details");
  expect(host.querySelector("ol")).toBeNull();
});
it("prevents duplicate acceptance while a write is in flight", async () => {
  await click(); let finish!: (value: unknown) => void; api.accept.mockReturnValueOnce(new Promise(resolve => { finish = resolve; }));
  await accept(); await accept(); expect(api.accept).toHaveBeenCalledTimes(1);
  finish({ taskId: 9, attemptId: 12, headSha: head, assignee: "reviewer" }); await flush();
  expect(host.textContent).toContain("已分派给 reviewer");
});
it("ignores an acceptance response after changing tenant", async () => {
  await click(); let finish!: (value: unknown) => void; api.accept.mockReturnValueOnce(new Promise(resolve => { finish = resolve; }));
  await accept(); const signal = api.accept.mock.calls[0]![2].signal as AbortSignal; setActiveTenant("other"); await flush();
  expect(signal.aborted).toBe(true); finish({ taskId: 9, attemptId: 12, headSha: head, assignee: "reviewer" }); await flush();
  expect(host.textContent).not.toContain("已分派给"); expect(host.querySelector("ol")).toBeNull();
  expect(onAssigned).not.toHaveBeenCalled();
});
it("rejects a mismatched assignment confirmation", async () => {
  await click(); api.accept.mockResolvedValueOnce({ taskId: 10, attemptId: 12, headSha: head, assignee: "reviewer" }); await accept();
  expect(host.textContent).toContain("分派结果未确认"); expect(host.textContent).not.toContain("已分派给");
});

it("shows rename relationships without inflating the covered file count", async () => {
  const data = response(); data.candidates[0]!.paths = ["src/A.java", "old/A.java"];
  data.basis.push({ path: "old/A.java", pattern: "old/**", line: 4, owners: ["@team/backend"], changedFiles: ["src/A.java"] });
  api.fetch.mockResolvedValueOnce(data); await click(); expect(host.textContent).toContain("覆盖 1 个文件，关联 2 条 Finding");
  expect(host.textContent).toContain("作为重命名前路径，关联变更：src/A.java");
});
