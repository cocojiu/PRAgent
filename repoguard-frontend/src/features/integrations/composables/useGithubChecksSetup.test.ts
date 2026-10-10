import { afterEach, describe, expect, it, vi } from "vitest";
import { effectScope, ref, type EffectScope } from "vue";
import { clearActiveTenant, setActiveTenant } from "@/stores/tenantContext";
import type { GithubChecksSetupStatus } from "@/types";
import { useGithubChecksSetup } from "./useGithubChecksSetup";

const scopes: EffectScope[] = [];
const scopedSetup = (options: Parameters<typeof useGithubChecksSetup>[0]) => {
  const scope = effectScope(); scopes.push(scope);
  return scope.run(() => useGithubChecksSetup(options))!;
};
const deferred = <T>() => {
  let resolve!: (value: T) => void; let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
};
const fixture = async () => {
  const requests = {
    fetch: vi.fn().mockResolvedValue(setupStatus(7)),
    preview: vi.fn().mockResolvedValue(setupStatus(7)),
    updatePolicy: vi.fn().mockResolvedValue({ ...setupStatus(8), repositoryCheckRunEnabled: true })
  };
  const canManage = ref(true);
  const setup = scopedSetup({ canManage, requests });
  setup.organization.value = "octo"; setup.repository.value = "repo"; setup.pullRequestNumber.value = 19;
  await setup.load();
  return { setup, requests, canManage };
};
afterEach(() => { scopes.splice(0).forEach(scope => scope.stop()); clearActiveTenant(); });

describe("useGithubChecksSetup", () => {
  it("loads, previews and updates a repository with the latest policy version", async () => {
    const status = setupStatus(7);
    const requests = {
      fetch: vi.fn().mockResolvedValue(status),
      preview: vi.fn().mockResolvedValue({ ...status, preview: { ...status.preview, attempted: true } }),
      updatePolicy: vi.fn().mockResolvedValue({
        ...status,
        repositoryCheckRunEnabled: true,
        effectiveCheckRunEnabled: true,
        policyVersion: 8
      })
    };
    const setup = scopedSetup({ canManage: ref(true), requests });
    setup.organization.value = " octo ";
    setup.repository.value = " repo ";
    setup.pullRequestNumber.value = 19;

    await setup.load();
    await setup.preview();
    await setup.setEnabled(true);

    expect(requests.fetch).toHaveBeenCalledWith("octo", "repo", { signal: expect.any(AbortSignal) });
    expect(requests.preview).toHaveBeenCalledWith({
      organization: "octo",
      repository: "repo",
      pullRequestNumber: 19
    });
    expect(requests.updatePolicy).toHaveBeenCalledWith({
      organization: "octo",
      repository: "repo",
      enabled: true,
      expectedVersion: 7,
      confirmed: true
    });
    expect(setup.status.value?.policyVersion).toBe(8);
  });

  it("does not mutate when the operator is not allowed to manage settings", async () => {
    const requests = {
      fetch: vi.fn(),
      preview: vi.fn(),
      updatePolicy: vi.fn()
    };
    const setup = scopedSetup({ canManage: ref(false), requests });
    setup.organization.value = "octo";
    setup.repository.value = "repo";
    setup.pullRequestNumber.value = 1;

    await setup.preview();
    await setup.setEnabled(true);

    expect(requests.preview).not.toHaveBeenCalled();
    expect(requests.updatePolicy).not.toHaveBeenCalled();
  });

  it("clears old evidence immediately when the repository changes", async () => {
    const { setup, requests } = await fixture();
    setup.repository.value = "another";
    expect(setup.status.value).toBeUndefined(); expect(setup.canEnable.value).toBe(false);
    expect(await setup.setEnabled(true)).toBe(false); await setup.preview();
    expect(requests.updatePolicy).not.toHaveBeenCalled(); expect(requests.preview).not.toHaveBeenCalled();
  });

  it("cancels an older read and prevents its result or finally from replacing the new read", async () => {
    const { setup, requests } = await fixture();
    const old = deferred<GithubChecksSetupStatus>(); const latest = deferred<GithubChecksSetupStatus>();
    requests.fetch.mockReturnValueOnce(old.promise).mockReturnValueOnce(latest.promise);
    const first = setup.load(); const oldSignal = requests.fetch.mock.calls[1]![2].signal as AbortSignal;
    const second = setup.load(); old.resolve(setupStatus(1)); await first;
    expect(oldSignal.aborted).toBe(true); expect(setup.loading.value).toBe(true); expect(setup.status.value).toBeUndefined();
    latest.resolve(setupStatus(9)); await second;
    expect(setup.status.value?.policyVersion).toBe(9); expect(setup.loading.value).toBe(false);
  });

  it("ignores an old repository error after the new repository read succeeds", async () => {
    const { setup, requests } = await fixture(); const old = deferred<GithubChecksSetupStatus>();
    requests.fetch.mockReturnValueOnce(old.promise); const first = setup.load();
    setup.repository.value = "another";
    requests.fetch.mockResolvedValueOnce({ ...setupStatus(9), repository: "another" }); await setup.load();
    old.reject(new Error("old failure")); await first;
    expect(setup.errorMessage.value).toBe(""); expect(setup.status.value?.repository).toBe("another");
  });

  it("keeps repository self-check valid when the PR changes during its read", async () => {
    const { setup, requests } = await fixture(); const pending = deferred<GithubChecksSetupStatus>();
    requests.fetch.mockReturnValueOnce(pending.promise); const read = setup.load();
    setup.pullRequestNumber.value = 20; pending.resolve(setupStatus(9)); await read;
    expect(setup.loading.value).toBe(false); expect(setup.canEnable.value).toBe(true);
  });

  it("rejects mismatched self-check evidence", async () => {
    const { setup, requests } = await fixture();
    requests.fetch.mockResolvedValueOnce({ ...setupStatus(9), repository: "wrong" }); await setup.load();
    expect(setup.status.value).toBeUndefined(); expect(setup.errorMessage.value).toContain("不一致");
    expect(await setup.setEnabled(true)).toBe(false);
  });

  it.each(["repository", "permission", "tenant", "refresh", "dispose"])("does not save if %s changes while confirmation is open", async kind => {
    const { setup, requests, canManage } = await fixture(); const confirmation = deferred<void>();
    const write = setup.confirmPolicyChange(true, () => confirmation.promise);
    if (kind === "repository") setup.repository.value = "another";
    if (kind === "permission") { canManage.value = false; canManage.value = true; }
    if (kind === "tenant") { setActiveTenant("other"); clearActiveTenant(); }
    if (kind === "refresh") await setup.load();
    if (kind === "dispose") scopes.at(-1)!.stop();
    confirmation.resolve(); expect(await write).toBe(false);
    expect(requests.updatePolicy).not.toHaveBeenCalled(); expect(setup.confirming.value).toBe(false);
  });

  it("suppresses a duplicate confirmation and treats cancel as no write", async () => {
    const { setup, requests } = await fixture(); const confirmation = deferred<void>(); const confirm = vi.fn(() => confirmation.promise);
    const write = setup.confirmPolicyChange(true, confirm);
    expect(await setup.confirmPolicyChange(true, confirm)).toBe(false); expect(confirm).toHaveBeenCalledTimes(1);
    confirmation.reject("cancel"); expect(await write).toBe(false); expect(requests.updatePolicy).not.toHaveBeenCalled();
  });

  it("returns true only for an acknowledged update of the current repository", async () => {
    const { setup, requests } = await fixture();
    expect(await setup.confirmPolicyChange(true, () => Promise.resolve())).toBe(true);
    expect(setup.canEnable.value).toBe(false); expect(setup.canDisable.value).toBe(true);
    expect(requests.updatePolicy).toHaveBeenCalledTimes(1);
  });

  it("does not report an unconfirmed update as success", async () => {
    const { setup, requests } = await fixture(); requests.updatePolicy.mockResolvedValueOnce(setupStatus(8));
    expect(await setup.setEnabled(true)).toBe(false); expect(setup.errorMessage.value).toContain("未确认");
  });

  it("retains the mutation lock until a stale update settles without attributing it to a new target", async () => {
    const { setup, requests } = await fixture(); const pending = deferred<GithubChecksSetupStatus>();
    requests.updatePolicy.mockReturnValueOnce(pending.promise); const write = setup.setEnabled(true);
    setup.repository.value = "another"; await setup.load(); expect(requests.fetch).toHaveBeenCalledTimes(1);
    expect(await setup.setEnabled(true)).toBe(false); expect(setup.saving.value).toBe(true);
    pending.resolve({ ...setupStatus(8), repositoryCheckRunEnabled: true });
    expect(await write).toBe(false); expect(setup.status.value).toBeUndefined(); expect(setup.saving.value).toBe(false);
  });

  it("never attributes a repository's historical preview to the selected PR", async () => {
    const { setup } = await fixture(); expect(setup.previewMatchesTarget.value).toBe(false);
    await setup.preview(); expect(setup.previewMatchesTarget.value).toBe(true);
    setup.pullRequestNumber.value = 20; expect(setup.previewMatchesTarget.value).toBe(false);
  });

  it("ignores a late preview of a previous PR and keeps its mutation lock", async () => {
    const { setup, requests } = await fixture(); const pending = deferred<GithubChecksSetupStatus>();
    requests.preview.mockReturnValueOnce(pending.promise); const preview = setup.preview();
    setup.pullRequestNumber.value = 20; await setup.preview(); expect(requests.preview).toHaveBeenCalledTimes(1);
    pending.resolve(setupStatus(9)); await preview;
    expect(setup.previewMatchesTarget.value).toBe(false); expect(setup.status.value?.policyVersion).toBe(7);
    expect(setup.previewing.value).toBe(false);
  });

  it("aborts a read on disposal and ignores a late rejected response", async () => {
    const { setup, requests } = await fixture(); const pending = deferred<GithubChecksSetupStatus>();
    requests.fetch.mockReturnValueOnce(pending.promise); const read = setup.load(); const signal = requests.fetch.mock.calls[1]![2].signal as AbortSignal;
    scopes.at(-1)!.stop(); expect(signal.aborted).toBe(true); pending.reject(new Error("late failure")); await read;
    expect(setup.status.value).toBeUndefined(); expect(setup.errorMessage.value).toBe("");
  });
});

const setupStatus = (policyVersion: number): GithubChecksSetupStatus => ({
  organization: "octo",
  repository: "repo",
  appEnabled: true,
  appConfigured: true,
  installationId: 77,
  installationAllowlisted: true,
  repositoryAuthorized: true,
  metadataPermission: true,
  contentsPermission: true,
  pullRequestsPermission: true,
  checksPermission: true,
  globalCheckRunEnabled: true,
  repositoryCheckRunEnabled: false,
  effectiveCheckRunEnabled: false,
  policyVersion,
  webhook: {
    endpointUrl: "/api/v1/github/webhooks",
    enabled: true,
    signatureRequired: true,
    secretConfigured: true,
    repositoriesRestricted: true,
    branchesRestricted: true,
    lastDeliveryStatus: "NOT_OBSERVED"
  },
  diagnostics: [],
  preview: {
    attempted: false,
    created: false,
    desiredStage: "NOT_CREATED",
    desiredVersion: 0,
    appliedVersion: 0,
    retryAttempts: 0,
    annotationCount: 0,
    annotationTruncated: false,
    status: "NOT_ATTEMPTED",
    message: "not attempted"
  },
  ready: true,
  mergeGateGuidance: "manual"
});
