import { computed, ref, type Ref } from "vue";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { ReviewTaskDetail } from "@/types";

const {
  fetchReviewChangedFiles,
  fetchReviewFindings,
  fetchReviewMissingTests,
  fetchReviewTimeline,
  showError,
  showWarning
} = vi.hoisted(() => ({
  fetchReviewChangedFiles: vi.fn(),
  fetchReviewFindings: vi.fn(),
  fetchReviewMissingTests: vi.fn(),
  fetchReviewTimeline: vi.fn(),
  showError: vi.fn(),
  showWarning: vi.fn()
}));

vi.mock("@/api/reviews", () => ({
  fetchReviewChangedFiles,
  fetchReviewFindings,
  fetchReviewMissingTests,
  fetchReviewTimeline
}));
vi.mock("element-plus/es/components/message/index.mjs", () => ({
  ElMessage: { error: showError, warning: showWarning }
}));

import { useReviewDetailSectionLoaders } from "./useReviewDetailSectionLoaders";

describe("useReviewDetailSectionLoaders", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("loads each heavy section with bounded parameters and updates the summary shell", async () => {
    fetchReviewFindings.mockResolvedValue({ items: [{ id: 11 }], total: 41 });
    fetchReviewChangedFiles.mockResolvedValue({ items: [{ path: "src/App.java" }], total: 12 });
    fetchReviewMissingTests.mockResolvedValue({ items: [{ file: "src/App.java" }], total: 3 });
    fetchReviewTimeline.mockResolvedValue([{ label: "完成", time: "now", status: "completed" }]);
    const selectedTask = ref<ReviewTaskDetail | null>(task(7));
    const sections = createSections(selectedTask);

    await sections.loadFindingsPage(2);
    await sections.loadChangedFilesPage(3);
    await sections.loadMissingTestsPage(1);
    await sections.loadTimelineItems();

    expect(fetchReviewFindings).toHaveBeenCalledWith(7, { page: 2, pageSize: 20 }, { signal: expect.any(AbortSignal) });
    expect(fetchReviewChangedFiles).toHaveBeenCalledWith(7, { page: 3, pageSize: 20 }, { signal: expect.any(AbortSignal) });
    expect(fetchReviewMissingTests).toHaveBeenCalledWith(7, { page: 1, pageSize: 20 }, { signal: expect.any(AbortSignal) });
    expect(fetchReviewTimeline).toHaveBeenCalledWith(7, { limit: 20 }, { signal: expect.any(AbortSignal) });
    expect(selectedTask.value?.findingTotal).toBe(41);
    expect(selectedTask.value?.changedFileTotal).toBe(12);
    expect(selectedTask.value?.missingTestTotal).toBe(3);
    expect(selectedTask.value?.timeline).toHaveLength(1);
    expect(sections.findingsPage.value).toBe(2);
    expect(sections.changedFilesPage.value).toBe(3);
    expect(sections.timelineLoaded.value).toBe(true);
  });

  it("invalidates stale work without clearing or reporting a newer request", async () => {
    const first = deferred<{ items: unknown[]; total: number }>();
    const second = deferred<{ items: unknown[]; total: number }>();
    fetchReviewFindings.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise);
    const selectedTask = ref<ReviewTaskDetail | null>(task(1));
    const sections = createSections(selectedTask);

    const firstLoad = sections.loadFindingsPage(1);
    sections.resetDetailSections();
    selectedTask.value = task(2);
    const secondLoad = sections.loadFindingsPage(2);

    first.reject(new Error("stale"));
    await firstLoad;
    expect(sections.findingsLoading.value).toBe(true);
    expect(showError).not.toHaveBeenCalled();

    second.resolve({ items: [{ id: 22 }], total: 1 });
    await secondLoad;
    expect(selectedTask.value?.id).toBe(2);
    expect(selectedTask.value?.findings).toEqual([{ id: 22 }]);
    expect(sections.findingsLoading.value).toBe(false);
  });

  it("keeps archived section rows empty without requesting deleted detail data", async () => {
    const selectedTask = ref<ReviewTaskDetail | null>(task(9, true));
    selectedTask.value!.findings = [{ id: 1 }] as never;
    selectedTask.value!.changedFiles = [{ path: "old" }] as never;
    selectedTask.value!.missingTests = [{ file: "old" }] as never;
    const sections = createSections(selectedTask);

    await sections.loadFindingsPage(2);
    await sections.loadChangedFilesPage(2);
    await sections.loadMissingTestsPage(2);

    expect(fetchReviewFindings).not.toHaveBeenCalled();
    expect(fetchReviewChangedFiles).not.toHaveBeenCalled();
    expect(fetchReviewMissingTests).not.toHaveBeenCalled();
    expect(selectedTask.value?.findings).toEqual([]);
    expect(selectedTask.value?.changedFiles).toEqual([]);
    expect(selectedTask.value?.missingTests).toEqual([]);
    expect(sections.findingsLoaded.value).toBe(true);
  });

  it("reloads the first findings page when the source filter changes", async () => {
    fetchReviewFindings.mockResolvedValue({ items: [{ id: 31, source: "SARIF" }], total: 1 });
    const selectedTask = ref<ReviewTaskDetail | null>(task(12));
    const sections = createSections(selectedTask);

    await sections.setFindingsSource("SARIF");

    expect(fetchReviewFindings).toHaveBeenCalledWith(12, { page: 1, pageSize: 20, source: "SARIF" }, { signal: expect.any(AbortSignal) });
    expect(sections.findingsSource.value).toBe("SARIF");
    expect(sections.findingsPage.value).toBe(1);
    expect(selectedTask.value?.findings[0]).toMatchObject({ id: 31, source: "SARIF" });
  });
  it("cancels an in-flight page and applies the latest page intent despite a delayed old response", async () => {
    const first = deferred<{ items: unknown[]; total: number }>();
    const second = deferred<{ items: unknown[]; total: number }>();
    fetchReviewFindings.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise);
    const selectedTask = ref<ReviewTaskDetail | null>(task(8));
    const sections = createSections(selectedTask);
    const oldLoad = sections.loadFindingsPage(1);
    const oldSignal = fetchReviewFindings.mock.calls[0]![2].signal as AbortSignal;
    const newLoad = sections.loadFindingsPage(3);
    expect(oldSignal.aborted).toBe(true);
    second.resolve({ items: [{ id: 33 }], total: 80 });
    await newLoad;
    first.resolve({ items: [{ id: 11 }], total: 40 });
    await oldLoad;
    expect(sections.findingsPage.value).toBe(3);
    expect(selectedTask.value?.findings).toEqual([{ id: 33 }]);
    expect(selectedTask.value?.findingTotal).toBe(80);
    expect(showError).not.toHaveBeenCalled();
  });

  it("aborts both paged sections and timeline on task change and suppresses cancelled failures", async () => {
    const files = deferred<{ items: unknown[]; total: number }>();
    const timeline = deferred<unknown[]>();
    fetchReviewChangedFiles.mockReturnValueOnce(files.promise);
    fetchReviewTimeline.mockReturnValueOnce(timeline.promise);
    const selectedTask = ref<ReviewTaskDetail | null>(task(8));
    const sections = createSections(selectedTask);
    const filesLoad = sections.loadChangedFilesPage(1);
    const timelineLoad = sections.loadTimelineItems();
    const fileSignal = fetchReviewChangedFiles.mock.calls[0]![2].signal as AbortSignal;
    const timelineSignal = fetchReviewTimeline.mock.calls[0]![2].signal as AbortSignal;
    selectedTask.value = task(9);
    expect(fileSignal.aborted).toBe(true);
    expect(timelineSignal.aborted).toBe(true);
    files.reject(new DOMException("cancelled", "AbortError"));
    timeline.reject(new DOMException("cancelled", "AbortError"));
    await Promise.all([filesLoad, timelineLoad]);
    expect(selectedTask.value?.changedFiles).toEqual([]);
    expect(sections.changedFilesLoading.value).toBe(false);
    expect(sections.timelineLoading.value).toBe(false);
    expect(showError).not.toHaveBeenCalled();
    expect(showWarning).not.toHaveBeenCalled();
  });
});

const createSections = (selectedTask: Ref<ReviewTaskDetail | null>) =>
  useReviewDetailSectionLoaders({
    isArchivedTask: computed(() => selectedTask.value?.archived === true),
    selectedTask
  });

const task = (id: number, archived = false) => ({
  id,
  archived,
  findings: [],
  findingTotal: 0,
  changedFiles: [],
  changedFileTotal: 0,
  missingTests: [],
  missingTestTotal: 0,
  timeline: []
}) as unknown as ReviewTaskDetail;

const deferred = <T>() => {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((promiseResolve, promiseReject) => {
    resolve = promiseResolve;
    reject = promiseReject;
  });
  return { promise, reject, resolve };
};
