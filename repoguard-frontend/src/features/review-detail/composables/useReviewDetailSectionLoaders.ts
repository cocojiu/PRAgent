import { ref, watch, type Ref } from "vue";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import {
  fetchReviewChangedFiles,
  fetchReviewFindings,
  fetchReviewMissingTests,
  fetchReviewTimeline
} from "@/api/reviews";
import type {
  ChangedFileViewModel,
  MissingTestViewModel,
  ReviewFindingViewModel,
  ReviewTaskDetail
} from "@/types";
import { getErrorMessage } from "@/utils/errors";
import {
  toChangedFileViewModel,
  toMissingTestViewModel,
  toReviewFindingViewModel,
  toTimelineItemViewModel
} from "../reviewDetailTaskMappers";

export const DETAIL_SECTION_PAGE_SIZE = 20;

type UseReviewDetailSectionLoadersOptions = {
  isArchivedTask: Readonly<Ref<boolean>>;
  selectedTask: Ref<ReviewTaskDetail | null>;
};

type PagedSectionState = ReturnType<typeof createPagedSectionState>;

const createPagedSectionState = () => ({
  loaded: ref(false),
  loading: ref(false),
  page: ref(1),
  requestSequence: 0,
  controller: undefined as AbortController | undefined
});

export const useReviewDetailSectionLoaders = ({
  isArchivedTask,
  selectedTask
}: UseReviewDetailSectionLoadersOptions) => {
  const findings = createPagedSectionState();
  const findingsSource = ref<string | undefined>(undefined);
  const changedFiles = createPagedSectionState();
  const missingTests = createPagedSectionState();
  const timelineLoaded = ref(false);
  const timelineLoading = ref(false);
  let timelineRequestSequence = 0;
  let timelineController: AbortController | undefined;

  const resetPagedSection = (state: PagedSectionState) => {
    state.requestSequence += 1;
    state.controller?.abort();
    state.controller = undefined;
    state.page.value = 1;
    state.loaded.value = false;
    state.loading.value = false;
  };

  const resetDetailSections = () => {
    resetPagedSection(findings);
    findingsSource.value = undefined;
    resetPagedSection(changedFiles);
    resetPagedSection(missingTests);
    timelineRequestSequence += 1;
    timelineController?.abort();
    timelineController = undefined;
    timelineLoaded.value = false;
    timelineLoading.value = false;
  };

  const isCurrentPagedRequest = (state: PagedSectionState, sequence: number, taskId: number) =>
    state.requestSequence === sequence && selectedTask.value?.id === taskId;

  const loadPagedSection = async <T>({
    applyResult,
    clearArchived,
    fetchPage,
    page,
    state
  }: {
    applyResult: (task: ReviewTaskDetail, items: T[], total: number) => ReviewTaskDetail;
    clearArchived: (task: ReviewTaskDetail) => ReviewTaskDetail;
    fetchPage: (taskId: number, signal: AbortSignal) => Promise<{ items: T[]; total: number }>;
    page: number;
    state: PagedSectionState;
  }) => {
    const task = selectedTask.value;
    if (!task) {
      return;
    }
    state.controller?.abort();
    if (isArchivedTask.value) {
      selectedTask.value = clearArchived(task);
      state.page.value = page;
      state.loaded.value = true;
      return;
    }

    const taskId = task.id;
    const sequence = ++state.requestSequence;
    const controller = new AbortController();
    state.controller = controller;
    state.loading.value = true;
    try {
      const result = await fetchPage(taskId, controller.signal);
      if (!isCurrentPagedRequest(state, sequence, taskId) || !selectedTask.value) {
        return;
      }
      selectedTask.value = applyResult(selectedTask.value, result.items, result.total);
      state.page.value = page;
      state.loaded.value = true;
    } catch (error) {
      if (!controller.signal.aborted && isCurrentPagedRequest(state, sequence, taskId)) {
        ElMessage.error(getErrorMessage(error, "请求失败"));
      }
    } finally {
      if (state.requestSequence === sequence) {
        state.loading.value = false;
        state.controller = undefined;
      }
    }
  };

  const loadFindingsPage = (page: number) =>
    loadPagedSection<ReviewFindingViewModel>({
      applyResult: (task, items, total) => ({ ...task, findings: items, findingTotal: total }),
      clearArchived: (task) => ({ ...task, findings: [] }),
      fetchPage: async (taskId, signal) => {
        const params: { page: number; pageSize: number; source?: string } = {
          page,
          pageSize: DETAIL_SECTION_PAGE_SIZE
        };
        if (findingsSource.value) {
          params.source = findingsSource.value;
        }
        const result = await fetchReviewFindings(taskId, params, { signal });
        return { items: result.items.map(toReviewFindingViewModel), total: result.total };
      },
      page,
      state: findings
    });

  const loadChangedFilesPage = (page: number) =>
    loadPagedSection<ChangedFileViewModel>({
      applyResult: (task, items, total) => ({ ...task, changedFiles: items, changedFileTotal: total }),
      clearArchived: (task) => ({ ...task, changedFiles: [] }),
      fetchPage: async (taskId, signal) => {
        const result = await fetchReviewChangedFiles(taskId, { page, pageSize: DETAIL_SECTION_PAGE_SIZE }, { signal });
        return { items: result.items.map(toChangedFileViewModel), total: result.total };
      },
      page,
      state: changedFiles
    });

  const loadMissingTestsPage = (page: number) =>
    loadPagedSection<MissingTestViewModel>({
      applyResult: (task, items, total) => ({ ...task, missingTests: items, missingTestTotal: total }),
      clearArchived: (task) => ({ ...task, missingTests: [] }),
      fetchPage: async (taskId, signal) => {
        const result = await fetchReviewMissingTests(taskId, { page, pageSize: DETAIL_SECTION_PAGE_SIZE }, { signal });
        return { items: result.items.map(toMissingTestViewModel), total: result.total };
      },
      page,
      state: missingTests
    });

  const loadTimelineItems = async () => {
    const task = selectedTask.value;
    if (!task || timelineLoading.value) {
      return;
    }
    const taskId = task.id;
    const sequence = ++timelineRequestSequence;
    const controller = new AbortController();
    timelineController = controller;
    timelineLoading.value = true;
    try {
      const timeline = (await fetchReviewTimeline(taskId, { limit: DETAIL_SECTION_PAGE_SIZE }, { signal: controller.signal }))
        .map(toTimelineItemViewModel);
      if (timelineRequestSequence !== sequence || selectedTask.value?.id !== taskId) {
        return;
      }
      selectedTask.value = { ...selectedTask.value, timeline };
      timelineLoaded.value = true;
    } catch (error) {
      if (!controller.signal.aborted && timelineRequestSequence === sequence && selectedTask.value?.id === taskId) {
        ElMessage.warning(getErrorMessage(error, "时间线加载失败"));
      }
    } finally {
      if (timelineRequestSequence === sequence) {
        timelineLoading.value = false;
        timelineController = undefined;
      }
    }
  };

  watch(() => selectedTask.value?.id, resetDetailSections, { flush: "sync" });

  return {
    changedFilesLoaded: changedFiles.loaded,
    changedFilesLoading: changedFiles.loading,
    changedFilesPage: changedFiles.page,
    findingsLoaded: findings.loaded,
    findingsLoading: findings.loading,
    findingsPage: findings.page,
    findingsSource,
    loadChangedFilesFirstPage: () => loadChangedFilesPage(1),
    loadChangedFilesPage,
    loadFindingsFirstPage: () => loadFindingsPage(1),
    loadFindingsPage,
    setFindingsSource: (source?: string) => {
      findingsSource.value = source || undefined;
      resetPagedSection(findings);
      return loadFindingsPage(1);
    },
    loadMissingTestsFirstPage: () => loadMissingTestsPage(1),
    loadMissingTestsPage,
    loadTimelineItems,
    missingTestsLoaded: missingTests.loaded,
    missingTestsLoading: missingTests.loading,
    missingTestsPage: missingTests.page,
    resetDetailSections,
    timelineLoaded,
    timelineLoading
  };
};
