import { describe, expect, it, vi } from "vitest";
import { ref } from "vue";
import type { GithubChecksSetupStatus } from "@/types";
import { useGithubChecksSetup } from "./useGithubChecksSetup";

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
    const setup = useGithubChecksSetup({ canManage: ref(true), requests });
    setup.organization.value = " octo ";
    setup.repository.value = " repo ";
    setup.pullRequestNumber.value = 19;

    await setup.load();
    await setup.preview();
    await setup.setEnabled(true);

    expect(requests.fetch).toHaveBeenCalledWith("octo", "repo");
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
    const setup = useGithubChecksSetup({ canManage: ref(false), requests });
    setup.organization.value = "octo";
    setup.repository.value = "repo";
    setup.pullRequestNumber.value = 1;

    await setup.preview();
    await setup.setEnabled(true);

    expect(requests.preview).not.toHaveBeenCalled();
    expect(requests.updatePolicy).not.toHaveBeenCalled();
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
