import { getCurrentScope, onScopeDispose, reactive } from "vue";
import { fetchGithubCommentPublicationItems } from "@/api/reviews";
import type { GithubCommentPublicationHistoryItem } from "@/types";
import { getErrorMessage } from "@/utils/errors";

const ITEM_PAGE_SIZE = 20;
export type GithubCommentHistoryItemsState = {
  expanded: boolean;
  loading: boolean;
  error: string;
  items: GithubCommentPublicationHistoryItem[];
  total: number;
  page: number;
  cursors: number[];
  nextAfterId: number | null;
  hasMore: boolean;
};

export const useGithubCommentHistoryItems = () => {
  const historyItems = reactive<Record<number, GithubCommentHistoryItemsState>>({});
  const controllers = new Map<number, AbortController>();
  let taskId: number | null = null;
  let disposed = false;

  const removeBatch = (batchId: number) => {
    controllers.get(batchId)?.abort();
    controllers.delete(batchId);
    delete historyItems[batchId];
  };
  const clearHistoryItems = () => {
    Object.keys(historyItems).forEach(key => removeBatch(Number(key)));
    taskId = null;
  };
  const retainHistoryItems = (id: number, batchIds: number[]) => {
    if (disposed) return;
    if (taskId !== id) clearHistoryItems();
    taskId = id;
    const visible = new Set(batchIds);
    Object.keys(historyItems).filter(key => !visible.has(Number(key))).forEach(key => removeBatch(Number(key)));
  };
  const loadPage = async (id: number, batchId: number, page: number, afterId: number) => {
    if (disposed || taskId !== id) return;
    const state = historyItems[batchId];
    if (!state?.expanded) return;
    controllers.get(batchId)?.abort();
    const controller = new AbortController();
    controllers.set(batchId, controller);
    const current = () => !disposed && taskId === id && historyItems[batchId] === state
      && state.expanded && controllers.get(batchId) === controller && !controller.signal.aborted;
    state.loading = true;
    state.error = "";
    try {
      const result = await fetchGithubCommentPublicationItems(id, batchId, { afterId, pageSize: ITEM_PAGE_SIZE }, { signal: controller.signal });
      if (!current()) return;
      if (result.taskId !== id || result.batchId !== batchId || result.items.length > ITEM_PAGE_SIZE
        || (result.hasMore && (result.nextAfterId == null || result.nextAfterId <= afterId))) {
        throw new Error("评论明细分页响应不符合当前批次");
      }
      state.items = result.items;
      state.total = result.total;
      state.hasMore = result.hasMore;
      state.nextAfterId = result.nextAfterId ?? null;
      state.page = page;
      state.cursors[page - 1] = afterId;
    } catch (error) {
      if (current()) state.error = getErrorMessage(error, "评论明细加载失败");
    } finally {
      if (current()) state.loading = false;
    }
  };
  const toggleHistoryBatch = async (id: number, batchId: number) => {
    if (disposed || taskId !== id) return;
    historyItems[batchId] ??= {
      expanded: false, loading: false, error: "", items: [], total: 0, page: 1,
      cursors: [0], nextAfterId: null, hasMore: false
    };
    const state = historyItems[batchId]!;
    state.expanded = !state.expanded;
    if (!state.expanded) {
      controllers.get(batchId)?.abort();
      controllers.delete(batchId);
      state.loading = false;
      return;
    }
    await loadPage(id, batchId, state.page, state.cursors[state.page - 1] ?? 0);
  };
  const changeHistoryItemsPage = async (id: number, batchId: number, direction: "next" | "previous") => {
    const state = historyItems[batchId];
    if (!state || state.loading || !state.expanded) return;
    if (direction === "next" && state.hasMore && state.nextAfterId !== null) {
      await loadPage(id, batchId, state.page + 1, state.nextAfterId);
    } else if (direction === "previous" && state.page > 1) {
      await loadPage(id, batchId, state.page - 1, state.cursors[state.page - 2] ?? 0);
    }
  };
  if (getCurrentScope()) onScopeDispose(() => { disposed = true; clearHistoryItems(); });
  return { historyItems, retainHistoryItems, clearHistoryItems, toggleHistoryBatch, changeHistoryItemsPage };
};
