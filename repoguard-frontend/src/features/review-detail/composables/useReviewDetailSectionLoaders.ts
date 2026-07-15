import { ref, type Ref } from "vue";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import {
  fetchReviewChangedFiles,
  fetchReviewFindings,
  fetchReviewMissingTests,
  fetchReviewTimeline
} from "@/api/reviews";
import type { ChangedFile, MissingTest, ReviewFinding, ReviewTaskDetail } from "@/types";
import { getErrorMessage } from "@/utils/errors";

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
  requestSequence: 0
});

export const useReviewDetailSectionLoaders = ({
  isArchivedTask,
  selectedTask
}: UseReviewDetailSectionLoadersOptions) => {
  const findings = createPagedSectionState();
  const changedFiles = createPagedSectionState();
  const missingTests = createPagedSectionState();
  const timelineLoaded = ref(false);
  const timelineLoading = ref(false);
  let timelineRequestSequence = 0;

  const resetPagedSection = (state: PagedSectionState) => {
    state.requestSequence += 1;
    state.page.value = 1;
    state.loaded.value = false;
    state.loading.value = false;
  };

  const resetDetailSections = () => {
    resetPagedSection(findings);
    resetPagedSection(changedFiles);
    resetPagedSection(missingTests);
    timelineRequestSequence += 1;
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
    fetchPage: (taskId: number) => Promise<{ items: T[]; total: number }>;
    page: number;
    state: PagedSectionState;
  }) => {
    const task = selectedTask.value;
    if (!task || state.loading.value) {
      return;
    }
    if (isArchivedTask.value) {
      selectedTask.value = clearArchived(task);
      state.page.value = page;
      state.loaded.value = true;
      return;
    }

    const taskId = task.id;
    const sequence = ++state.requestSequence;
    state.loading.value = true;
    try {
      const result = await fetchPage(taskId);
      if (!isCurrentPagedRequest(state, sequence, taskId) || !selectedTask.value) {
        return;
      }
      selectedTask.value = applyResult(selectedTask.value, result.items, result.total);
      state.page.value = page;
      state.loaded.value = true;
    } catch (error) {
      if (isCurrentPagedRequest(state, sequence, taskId)) {
        ElMessage.error(getErrorMessage(error, "请求失败"));
      }
    } finally {
      if (state.requestSequence === sequence) {
        state.loading.value = false;
      }
    }
  };

  const loadFindingsPage = (page: number) =>
    loadPagedSection<ReviewFinding>({
      applyResult: (task, items, total) => ({ ...task, findings: items, findingTotal: total }),
      clearArchived: (task) => ({ ...task, findings: [] }),
      fetchPage: (taskId) => fetchReviewFindings(taskId, { page, pageSize: DETAIL_SECTION_PAGE_SIZE }),
      page,
      state: findings
    });

  const loadChangedFilesPage = (page: number) =>
    loadPagedSection<ChangedFile>({
      applyResult: (task, items, total) => ({ ...task, changedFiles: items, changedFileTotal: total }),
      clearArchived: (task) => ({ ...task, changedFiles: [] }),
      fetchPage: (taskId) => fetchReviewChangedFiles(taskId, { page, pageSize: DETAIL_SECTION_PAGE_SIZE }),
      page,
      state: changedFiles
    });

  const loadMissingTestsPage = (page: number) =>
    loadPagedSection<MissingTest>({
      applyResult: (task, items, total) => ({ ...task, missingTests: items, missingTestTotal: total }),
      clearArchived: (task) => ({ ...task, missingTests: [] }),
      fetchPage: (taskId) => fetchReviewMissingTests(taskId, { page, pageSize: DETAIL_SECTION_PAGE_SIZE }),
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
    timelineLoading.value = true;
    try {
      const timeline = await fetchReviewTimeline(taskId, { limit: DETAIL_SECTION_PAGE_SIZE });
      if (timelineRequestSequence !== sequence || selectedTask.value?.id !== taskId) {
        return;
      }
      selectedTask.value = { ...selectedTask.value, timeline };
      timelineLoaded.value = true;
    } catch (error) {
      if (timelineRequestSequence === sequence && selectedTask.value?.id === taskId) {
        ElMessage.warning(getErrorMessage(error, "时间线加载失败"));
      }
    } finally {
      if (timelineRequestSequence === sequence) {
        timelineLoading.value = false;
      }
    }
  };

  return {
    changedFilesLoaded: changedFiles.loaded,
    changedFilesLoading: changedFiles.loading,
    changedFilesPage: changedFiles.page,
    findingsLoaded: findings.loaded,
    findingsLoading: findings.loading,
    findingsPage: findings.page,
    loadChangedFilesFirstPage: () => loadChangedFilesPage(1),
    loadChangedFilesPage,
    loadFindingsFirstPage: () => loadFindingsPage(1),
    loadFindingsPage,
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
