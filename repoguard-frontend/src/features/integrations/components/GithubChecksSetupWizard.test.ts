import { createApp, defineComponent, h, nextTick, ref, type App } from "vue";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { GithubChecksSetupStatus } from "@/types";
import Wizard from "./GithubChecksSetupWizard.vue";

/* eslint-disable vue/one-component-per-file -- UI stubs exercise the real wizard's confirmation and request lifecycle. */
const api = vi.hoisted(() => ({ fetch: vi.fn(), preview: vi.fn(), update: vi.fn() }));
const messages = vi.hoisted(() => ({ success: vi.fn(), confirm: vi.fn() }));
vi.mock("@/api/config", () => ({ fetchGithubChecksSetup: api.fetch, previewGithubChecks: api.preview, updateGithubChecksPolicy: api.update }));
vi.mock("element-plus", () => ({ ElMessage: { success: messages.success }, ElMessageBox: { confirm: messages.confirm } }));
let app: App | undefined;
let host: HTMLDivElement;
const permission = ref(true);
const flush = async () => { for (let i = 0; i < 6; i++) { await Promise.resolve(); await nextTick(); } };
const button = (text: string) => [...host.querySelectorAll("button")].find(node => node.textContent?.trim() === text)!;
const deferred = <T>() => {
  let resolve!: (value: T) => void; let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
};
const status = (enabled = false): GithubChecksSetupStatus => ({
  organization: "octo", repository: "repo", appEnabled: true, appConfigured: true, installationId: 77,
  installationAllowlisted: true, repositoryAuthorized: true, metadataPermission: true, contentsPermission: true,
  pullRequestsPermission: true, checksPermission: true, globalCheckRunEnabled: true,
  repositoryCheckRunEnabled: enabled, effectiveCheckRunEnabled: enabled, policyVersion: enabled ? 8 : 7,
  webhook: { endpointUrl: "/webhook", enabled: true, signatureRequired: true, secretConfigured: true,
    repositoriesRestricted: true, branchesRestricted: true, lastDeliveryStatus: "NOT_OBSERVED" },
  diagnostics: [], preview: { attempted: true, created: true, desiredStage: "PREVIEW", desiredVersion: 7, appliedVersion: 7,
    retryAttempts: 0, annotationCount: 0, annotationTruncated: false, status: "COMPLETED", message: "historical preview" },
  ready: true, mergeGateGuidance: "manual"
});
const mount = async () => {
  app = createApp(defineComponent({ setup: () => () => h(Wizard, { canManage: permission.value, initialOrganization: "octo", initialRepository: "repo" }) }));
  app.component("ElCard", defineComponent({ setup: (_, { slots }) => () => h("section", [slots.header?.(), slots.default?.()]) }));
  for (const name of ["ElTag", "ElAlert"]) app.component(name, defineComponent({ setup: (_, { slots }) => () => h("div", slots.default?.()) }));
  app.component("ElButton", defineComponent({ props: { loading: Boolean, disabled: Boolean },
    setup: (props, { slots }) => () => h("button", { disabled: props.loading || props.disabled }, slots.default?.()) }));
  app.component("ElInput", defineComponent({ props: { modelValue: { type: String, default: "" } }, emits: ["update:modelValue"],
    setup: (props, { attrs, emit }) => () => h("input", { ...attrs, value: props.modelValue,
      onInput: (event: Event) => emit("update:modelValue", (event.target as HTMLInputElement).value) }) }));
  app.component("ElInputNumber", defineComponent({ props: { modelValue: { type: Number, default: undefined } }, emits: ["update:modelValue"],
    setup: (props, { attrs, emit }) => () => h("input", { ...attrs, type: "number", value: props.modelValue,
      onInput: (event: Event) => emit("update:modelValue", Number((event.target as HTMLInputElement).value)) }) }));
  app.mount(host); await flush(); button("运行权限自检").click(); await flush();
};
beforeEach(() => {
  vi.resetAllMocks(); permission.value = true; api.fetch.mockResolvedValue(status()); api.update.mockResolvedValue(status(true));
  messages.confirm.mockResolvedValue("confirm"); host = document.createElement("div"); document.body.append(host);
});
afterEach(() => { app?.unmount(); app = undefined; host.remove(); });

describe("Checks wizard UI evidence", () => {
  it("does not display historical preview data as the selected PR's result", async () => {
    await mount(); expect(host.textContent).toContain("当前 PR 尚未取得预览结果"); expect(host.textContent).not.toContain("historical preview");
  });

  it("does not show success or update a new target after confirmation was opened for another repository", async () => {
    await mount(); const confirm = deferred<void>(); messages.confirm.mockReturnValueOnce(confirm.promise);
    button("确认启用本仓库 Check").click(); await flush();
    const repository = host.querySelector<HTMLInputElement>('[aria-label="GitHub 仓库"]')!;
    repository.value = "another"; repository.dispatchEvent(new Event("input")); await flush();
    expect(host.textContent).not.toContain("installation：77"); confirm.resolve(); await flush();
    expect(api.update).not.toHaveBeenCalled(); expect(messages.success).not.toHaveBeenCalled();
  });

  it("shows success only after the current repository update is acknowledged", async () => {
    await mount(); const update = deferred<GithubChecksSetupStatus>(); api.update.mockReturnValueOnce(update.promise);
    const enable = button("确认启用本仓库 Check"); enable.click(); enable.click(); await flush();
    expect(api.update).toHaveBeenCalledTimes(1); expect(messages.confirm).toHaveBeenCalledTimes(1); expect(messages.success).not.toHaveBeenCalled();
    update.resolve(status(true)); await flush(); expect(messages.success).toHaveBeenCalledTimes(1);
    expect(host.textContent).toContain("已确认启用"); expect(button("确认启用本仓库 Check").disabled).toBe(true);
  });

  it("shows save failure without a success toast", async () => {
    await mount(); api.update.mockRejectedValueOnce(new Error("policy conflict")); button("确认启用本仓库 Check").click(); await flush();
    expect(host.textContent).toContain("policy conflict"); expect(messages.success).not.toHaveBeenCalled();
  });

  it("ignores a confirmation after management permission was lost and restored", async () => {
    await mount(); const confirm = deferred<void>(); messages.confirm.mockReturnValueOnce(confirm.promise);
    button("确认启用本仓库 Check").click(); await flush(); permission.value = false; await flush(); permission.value = true; await flush();
    confirm.resolve(); await flush(); expect(api.update).not.toHaveBeenCalled(); expect(messages.success).not.toHaveBeenCalled();
  });

  it("does not show a late save success after the wizard is unmounted", async () => {
    await mount(); const update = deferred<GithubChecksSetupStatus>(); api.update.mockReturnValueOnce(update.promise);
    button("确认启用本仓库 Check").click(); await flush(); app!.unmount(); app = undefined;
    update.resolve(status(true)); await flush(); expect(messages.success).not.toHaveBeenCalled();
  });
});
