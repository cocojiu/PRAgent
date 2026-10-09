import { createApp, defineComponent, h, nextTick, type App } from "vue";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { currentUser } from "@/stores/authState";
import type { ConnectionTestResult } from "@/types";
import Page from "./IntegrationsPage.vue";

/* eslint-disable vue/one-component-per-file -- Stubs preserve the real page's card, draft and connection-result wiring. */
const api = vi.hoisted(() => ({ github: vi.fn(), mysql: vi.fn(), rabbit: vi.fn(), policy: vi.fn(), test: vi.fn() }));
const messages = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn(), warning: vi.fn() }));
vi.mock("element-plus/es/components/message/index.mjs", () => ({ ElMessage: messages }));
vi.mock("@/api/config", () => ({
  fetchGithubIntegrationConfig: api.github, fetchMysqlIntegrationConfig: api.mysql, fetchRabbitMqIntegrationConfig: api.rabbit, fetchReviewPolicyConfig: api.policy,
  testGithubIntegrationConnection: api.test, testMysqlConnection: vi.fn(), testRabbitMqConnection: vi.fn(), testReviewPolicyConnection: vi.fn(),
  fetchGithubChecksSetup: vi.fn(), previewGithubChecks: vi.fn(), updateGithubChecksPolicy: vi.fn(),
  updateGithubIntegrationConfig: vi.fn(), updateMysqlIntegrationConfig: vi.fn(), updateRabbitMqIntegrationConfig: vi.fn(), updateReviewPolicyConfig: vi.fn()
}));
vi.mock("@/features/integrations/components/GithubFeedbackPanel.vue", () => ({ default: { render: () => null } }));
vi.mock("@/features/integrations/components/GithubChecksSetupWizard.vue", () => ({ default: { render: () => null } }));
let app: App | undefined;
let host: HTMLDivElement;
const flush = async () => { for (let i = 0; i < 8; i++) { await Promise.resolve(); await nextTick(); } };
const githubCard = () => [...host.querySelectorAll("article")].find(node => node.querySelector("h2")?.textContent === "GitHub")!;
const testButton = () => [...githubCard().querySelectorAll("button")].find(node => node.textContent?.includes("测试连接"))!;
const editOwner = async (value: string) => {
  const label = [...githubCard().querySelectorAll("label")].find(node => node.textContent === "Default Owner")!;
  const input = label.parentElement!.querySelector("input")!; input.value = value; input.dispatchEvent(new Event("input")); await flush();
};
const success: ConnectionTestResult = { success: true, status: "connected", message: "mock connection healthy", checkedAt: "2026-10-09T12:00:00Z" };
const deferred = <T>() => {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(yes => { resolve = yes; }); return { promise, resolve };
};
beforeEach(async () => {
  vi.resetAllMocks(); currentUser.value = { id: 1, username: "admin", email: "admin@example.test", role: "ADMIN", status: "ACTIVE" };
  api.github.mockResolvedValue({ provider: "github", status: "configured", secretStatus: "configured", baseUrl: "https://api.example.test", token: "******", defaultOwner: "octo", defaultRepo: "repo" });
  api.mysql.mockResolvedValue({ provider: "mysql", status: "configured", baseUrl: "jdbc:mysql://example.test/db", secretStatus: "configured" });
  api.rabbit.mockResolvedValue({ provider: "rabbitmq", status: "configured", baseUrl: "amqp://example.test", secretStatus: "configured" });
  api.policy.mockResolvedValue({ llmEnabled: true, llmProvider: "dashscope", modelName: "test-model", secretStatus: "configured", timeoutSeconds: 60, temperature: .2, maxTokens: 4096 });
  api.test.mockResolvedValue(success); host = document.createElement("div"); document.body.append(host); app = createApp(Page);
  app.directive("loading", {});
  app.component("ElButton", defineComponent({ props: { loading: Boolean, disabled: Boolean },
    setup: (props, { slots }) => () => h("button", { disabled: props.loading || props.disabled }, slots.default?.()) }));
  app.component("ElAlert", defineComponent({ props: { title: { type: String, default: "" } },
    setup: (props, { slots }) => () => h("div", [props.title, slots.title?.(), slots.default?.()]) }));
  app.component("ElInput", defineComponent({ props: { modelValue: { type: String, default: "" } }, emits: ["update:modelValue"],
    setup: (props, { attrs, emit }) => () => h("input", { ...attrs, value: props.modelValue,
      onInput: (event: Event) => emit("update:modelValue", (event.target as HTMLInputElement).value) }) }));
  for (const name of ["ElSelect", "ElOption"]) app.component(name, defineComponent({ setup: () => () => null }));
  app.mount(host); await flush();
});
afterEach(() => { app?.unmount(); app = undefined; host.remove(); currentUser.value = undefined; });

describe("integration connection card", () => {
  it("replaces both connected labels when the tested draft is edited", async () => {
    testButton().click(); await flush(); expect(githubCard().textContent).toContain("mock connection healthy");
    await editOwner("another"); expect(githubCard().querySelectorAll(".integration-status.pending")).toHaveLength(2);
    expect(githubCard().textContent).toContain("待重新测试"); expect(githubCard().textContent).not.toContain("已连接");
    expect(githubCard().textContent).toContain("上次检测时间"); expect(githubCard().textContent).toContain("有未保存的修改");
  });

  it("ignores an old result and displays the result of an explicit test of the new draft", async () => {
    const pending = deferred<ConnectionTestResult>(); api.test.mockReturnValueOnce(pending.promise);
    testButton().click(); await flush(); expect(githubCard().textContent).toContain("检测中"); await editOwner("another");
    pending.resolve(success); await flush(); expect(githubCard().textContent).toContain("待重新测试"); expect(messages.success).not.toHaveBeenCalled();
    testButton().click(); await flush(); expect(api.test.mock.calls[1]![0].defaultOwner).toBe("another");
    expect(githubCard().textContent).toContain("mock connection healthy"); expect(githubCard().textContent).not.toContain("待重新测试");
    expect(messages.success).toHaveBeenCalledTimes(1);
  });

  it("ignores a late result after leaving the page", async () => {
    const pending = deferred<ConnectionTestResult>(); api.test.mockReturnValueOnce(pending.promise);
    testButton().click(); await flush(); app!.unmount(); app = undefined; pending.resolve(success); await flush();
    expect(messages.success).not.toHaveBeenCalled(); expect(host.textContent).toBe("");
  });
});
