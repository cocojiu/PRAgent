import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { effectScope, type EffectScope } from "vue";
import { currentUser } from "@/stores/authState";
import { useNotificationBindings } from "./useNotificationBindings";
import type { NotificationBinding } from "@/types";

const configApi = vi.hoisted(() => ({
  createNotificationBinding: vi.fn(),
  deleteNotificationBinding: vi.fn(),
  fetchNotificationBindings: vi.fn(),
  testNotificationBinding: vi.fn(),
  updateNotificationBinding: vi.fn(),
  updateNotificationBindingStatus: vi.fn()
}));

const messages = vi.hoisted(() => ({
  error: vi.fn(),
  success: vi.fn(),
  warning: vi.fn()
}));

vi.mock("@/api/config", () => configApi);
vi.mock("element-plus/es/components/message/index.mjs", () => ({
  ElMessage: messages
}));

describe("useNotificationBindings", () => {
  const scopes: EffectScope[] = [];
  const setup = () => { const scope = effectScope(); scopes.push(scope); return scope.run(useNotificationBindings)!; };
  beforeEach(() => {
    vi.resetAllMocks();
    currentUser.value = { id: 1, username: "admin", email: "admin@example.test", role: "ADMIN", status: "ACTIVE" };
  });
  afterEach(() => {
    scopes.splice(0).forEach(scope => scope.stop());
    vi.clearAllMocks();
    currentUser.value = undefined;
  });

  it("loads notification bindings with bounded server-side pagination", async () => {
    configApi.fetchNotificationBindings.mockResolvedValue({
      items: [binding(1)],
      total: 41
    });

    const bindings = setup();
    await bindings.loadNotificationBindings();

    expect(configApi.fetchNotificationBindings).toHaveBeenCalledWith({
      page: 1,
      pageSize: 20
    }, { signal: expect.any(AbortSignal) });
    expect(configApi.fetchNotificationBindings).not.toHaveBeenCalledWith(expect.objectContaining({ pageSize: 100 }));
    expect(bindings.notificationBindings.value).toHaveLength(1);
    expect(bindings.bindingTotal.value).toBe(41);
  });

  it("reloads the requested binding page and page size", async () => {
    configApi.fetchNotificationBindings
      .mockResolvedValueOnce({
        items: [binding(1)],
        total: 41
      })
      .mockResolvedValueOnce({
        items: [binding(21)],
        total: 41
      })
      .mockResolvedValueOnce({
        items: [binding(1), binding(2)],
        total: 41
      });

    const bindings = setup();
    await bindings.loadNotificationBindings();
    await bindings.changeBindingPage(2);
    await bindings.changeBindingPageSize(10);

    expect(configApi.fetchNotificationBindings).toHaveBeenNthCalledWith(2, {
      page: 2,
      pageSize: 20
    }, { signal: expect.any(AbortSignal) });
    expect(configApi.fetchNotificationBindings).toHaveBeenNthCalledWith(3, {
      page: 1,
      pageSize: 10
    }, { signal: expect.any(AbortSignal) });
  });

  it("returns to the first page after creating a binding", async () => {
    currentUser.value = {
      id: 1,
      username: "admin",
      email: "admin@example.com",
      role: "ADMIN",
      status: "ACTIVE"
    };
    configApi.fetchNotificationBindings
      .mockResolvedValueOnce({
        items: [binding(21)],
        total: 41
      })
      .mockResolvedValueOnce({
        items: [binding(42)],
        total: 42
      });
    configApi.createNotificationBinding.mockResolvedValue(binding(42));

    const bindings = setup();
    await bindings.changeBindingPage(2);
    bindings.openBindingDialog();
    await bindings.saveBinding();

    expect(configApi.createNotificationBinding).toHaveBeenCalled();
    expect(configApi.fetchNotificationBindings).toHaveBeenLastCalledWith({
      page: 1,
      pageSize: 20
    }, { signal: expect.any(AbortSignal) });
  });
});

const binding = (id: number): NotificationBinding => ({
  id,
  name: `binding-${id}`,
  provider: "DINGTALK",
  organization: "codex",
  repository: "repo-guard",
  enabled: true,
  notifyReviewCompleted: true,
  notifyReviewFailed: true,
  notifyHumanReviewRequired: true,
  notifyGithubComment: true,
  status: "CONFIGURED"
});
