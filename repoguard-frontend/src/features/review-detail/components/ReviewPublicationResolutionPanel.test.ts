/* eslint-disable vue/one-component-per-file -- Minimal DOM stubs exercise the real resolution panel. */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createApp, defineComponent, h, nextTick, type App } from "vue";
import { fetchPendingGithubPublications, verifyGithubPublication, closeGithubPublication } from "@/api/githubPublications";
import type { PendingGithubPublication } from "@/api/githubPublicationTypes";
import { setActiveTenant } from "@/stores/tenantContext";
import Panel from "./ReviewPublicationResolutionPanel.vue";
vi.mock("@/api/githubPublications", () => ({ fetchPendingGithubPublications: vi.fn(), verifyGithubPublication: vi.fn(), closeGithubPublication: vi.fn() }));
const dialog = vi.hoisted(() => ({ prompt: vi.fn() }));
vi.mock("element-plus", () => ({ ElMessageBox: dialog }));
const pending = (): PendingGithubPublication => ({ id: 42, findingId: null, targetType: "pull_request", status: "outcome_unknown", version: "a".repeat(64), verifiable: true, message: "结果待核验" });
const Button = defineComponent({ props: { disabled: Boolean }, setup: (props, { slots }) => () => h("button", { disabled: props.disabled }, slots.default?.()) });
const Input = defineComponent({ props: { modelValue: { type: String, default: "" }, disabled: Boolean }, emits: ["update:modelValue"], setup: (props, { emit }) => () => h("input", { value: props.modelValue, disabled: props.disabled, onInput: (event: Event) => emit("update:modelValue", (event.target as HTMLInputElement).value) }) });

const wrappers: Array<{ app: App; host: HTMLDivElement }> = [];
const flushPromises = async () => { for (let i = 0; i < 6; i++) { await Promise.resolve(); await nextTick(); } };
const fixture = () => {
  const host = document.createElement("div"); document.body.append(host);
  const resolved = vi.fn(); const app = createApp(Panel, { taskId: 7, onResolved: resolved });
  app.component("ElButton", Button); app.component("ElInput", Input);
  for (const name of ["ElSelect", "ElOption", "ElAlert"]) app.component(name, defineComponent({ setup: () => () => null }));
  app.mount(host); wrappers.push({ app, host });
  return { host, resolved };
};
type Fixture = ReturnType<typeof fixture>;
const button = (wrapper: Fixture, text: string) => [...wrapper.host.querySelectorAll("button")].find(item => item.textContent?.includes(text))!;
const setValue = async (wrapper: Fixture, value: string) => { const input = wrapper.host.querySelector("input")!; input.value = value; input.dispatchEvent(new Event("input")); await nextTick(); };
describe("publication resolution", () => {
  beforeEach(() => { vi.resetAllMocks(); setActiveTenant(); vi.mocked(fetchPendingGithubPublications).mockResolvedValue([pending()]); });
  afterEach(() => { wrappers.splice(0).forEach(({ app, host }) => { app.unmount(); host.remove(); }); });
  it("loads only on demand and accepts only positive safe integer reference IDs", async () => {
    const wrapper = fixture(); expect(fetchPendingGithubPublications).not.toHaveBeenCalled();
    button(wrapper, "加载").click(); await nextTick(); await flushPromises();
    const verify = button(wrapper, "只读核验"); expect(verify.disabled).toBe(true);
    for (const value of ["1e5", "-1", "9007199254740992"]) {
      await setValue(wrapper, value); expect(verify.disabled).toBe(true);
    }
    await setValue(wrapper, "123"); expect(verify.disabled).toBe(false);
  });
  it("verifies the exact version without calling the publish API", async () => {
    vi.mocked(verifyGithubPublication).mockResolvedValue({ ...pending(), status: "published" });
    const wrapper = fixture(); button(wrapper, "加载").click(); await nextTick(); await flushPromises();
    await setValue(wrapper, "123"); vi.mocked(fetchPendingGithubPublications).mockResolvedValue([]);
    button(wrapper, "只读核验").click(); await nextTick(); await flushPromises();
    expect(verifyGithubPublication).toHaveBeenCalledWith(7, 42, { version: "a".repeat(64), kind: "issue_comment", remoteId: 123 }, { signal: expect.any(AbortSignal) });
    expect(wrapper.resolved).toHaveBeenCalledTimes(1); expect(wrapper.host.textContent).toContain("当前没有待核验");
  });
  it("requires a deliberate no-retry closure and reason", async () => {
    dialog.prompt.mockResolvedValue({ value: "确认关闭这条记录", action: "confirm" });
    vi.mocked(closeGithubPublication).mockResolvedValue({ ...pending(), status: "closed" });
    const wrapper = fixture(); button(wrapper, "加载").click(); await nextTick(); await flushPromises();
    vi.mocked(fetchPendingGithubPublications).mockResolvedValue([]);
    button(wrapper, "关闭且不再").click(); await nextTick(); await flushPromises();
    expect(closeGithubPublication).toHaveBeenCalledWith(7, 42, { version: "a".repeat(64), reason: "确认关闭这条记录", confirmNoRetry: true }, { signal: expect.any(AbortSignal) });
  });
  it("aborts and ignores old tenant reads", async () => {
    let resolve!: (items: PendingGithubPublication[]) => void;
    vi.mocked(fetchPendingGithubPublications).mockImplementationOnce(() => new Promise(done => { resolve = done; }));
    const wrapper = fixture(); button(wrapper, "加载").click(); await nextTick();
    const signal = vi.mocked(fetchPendingGithubPublications).mock.calls[0]![2]!.signal!;
    setActiveTenant("another-tenant"); await flushPromises(); expect(signal.aborted).toBe(true);
    resolve([pending()]); await flushPromises(); expect(wrapper.host.textContent).not.toContain("回写记录 #42");
  });
  it("never confirms a cancelled close dialog", async () => {
    dialog.prompt.mockRejectedValue("cancel");
    const wrapper = fixture(); button(wrapper, "加载").click(); await nextTick(); await flushPromises();
    button(wrapper, "关闭且不再").click(); await nextTick(); await flushPromises(); expect(closeGithubPublication).not.toHaveBeenCalled();
  });
});
