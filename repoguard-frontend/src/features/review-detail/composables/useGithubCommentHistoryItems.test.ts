import { beforeEach, describe, expect, it, vi } from "vitest";
import { effectScope } from "vue";
import { fetchGithubCommentPublicationItems } from "@/api/reviews";
import type { GithubCommentPublicationItems } from "@/types";
import { useGithubCommentHistoryItems } from "./useGithubCommentHistoryItems";

vi.mock("@/api/reviews", () => ({ fetchGithubCommentPublicationItems: vi.fn() }));
const page = (afterId = 0, hasMore = true): GithubCommentPublicationItems => ({
  taskId: 521, batchId: 99, total: 45, pageSize: 20, afterId,
  nextAfterId: afterId + 20, hasMore,
  items: Array.from({ length: hasMore ? 20 : 5 }, (_, index) => ({
    findingId: afterId + index + 1, file: `file-${afterId + index + 1}`, targetType: "line", status: "published", success: true
  }))
});

describe("useGithubCommentHistoryItems", () => {
  const fetchItems = vi.mocked(fetchGithubCommentPublicationItems);
  beforeEach(() => vi.resetAllMocks());

  it("loads on expansion and replaces one bounded page with next and previous cursors", async () => {
    fetchItems.mockResolvedValueOnce(page()).mockResolvedValueOnce(page(20)).mockResolvedValueOnce(page());
    const history = useGithubCommentHistoryItems();
    history.retainHistoryItems(521, [99]);
    expect(fetchItems).not.toHaveBeenCalled();
    await history.toggleHistoryBatch(521, 99);
    await history.changeHistoryItemsPage(521, 99, "next");
    expect(fetchItems).toHaveBeenLastCalledWith(521, 99, { afterId: 20, pageSize: 20 }, { signal: expect.any(AbortSignal) });
    expect(history.historyItems[99]?.items).toHaveLength(20);
    expect(history.historyItems[99]?.items[0]?.findingId).toBe(21);
    await history.changeHistoryItemsPage(521, 99, "previous");
    expect(history.historyItems[99]?.page).toBe(1);
    expect(history.historyItems[99]?.items[0]?.findingId).toBe(1);
  });

  it("ignores collapsed or pruned batch responses and cancels their requests", async () => {
    let resolveRequest!: (value: GithubCommentPublicationItems) => void;
    fetchItems.mockImplementationOnce(() => new Promise(resolve => { resolveRequest = resolve; }));
    const history = useGithubCommentHistoryItems();
    history.retainHistoryItems(521, [99]);
    const request = history.toggleHistoryBatch(521, 99);
    const signal = fetchItems.mock.calls[0]![3]!.signal!;
    history.retainHistoryItems(521, [100]);
    resolveRequest(page());
    await request;
    expect(signal.aborted).toBe(true);
    expect(history.historyItems[99]).toBeUndefined();
  });

  it("rejects a nonadvancing cursor without hiding the error or accepting oversized rows", async () => {
    fetchItems.mockResolvedValueOnce({ ...page(), nextAfterId: 0 });
    const history = useGithubCommentHistoryItems();
    history.retainHistoryItems(521, [99]);
    await history.toggleHistoryBatch(521, 99);
    expect(history.historyItems[99]?.error).toContain("不符合当前批次");
    expect(history.historyItems[99]?.items).toHaveLength(0);
    expect(history.historyItems[99]?.loading).toBe(false);
  });

  it("keeps the current page on failure and clears cached rows on task change", async () => {
    fetchItems.mockResolvedValueOnce(page()).mockRejectedValueOnce(new Error("unavailable"));
    const history = useGithubCommentHistoryItems();
    history.retainHistoryItems(521, [99]);
    await history.toggleHistoryBatch(521, 99);
    await history.changeHistoryItemsPage(521, 99, "next");
    expect(history.historyItems[99]?.page).toBe(1);
    expect(history.historyItems[99]?.items).toHaveLength(20);
    expect(history.historyItems[99]?.error).toContain("unavailable");
    history.retainHistoryItems(522, [99]);
    expect(history.historyItems[99]).toBeUndefined();
  });

  it("disposes in-flight detail requests without creating a cache again", async () => {
    let resolveRequest!: (value: GithubCommentPublicationItems) => void;
    fetchItems.mockImplementationOnce(() => new Promise(resolve => { resolveRequest = resolve; }));
    const scope = effectScope();
    const history = scope.run(() => useGithubCommentHistoryItems())!;
    history.retainHistoryItems(521, [99]);
    const request = history.toggleHistoryBatch(521, 99);
    const signal = fetchItems.mock.calls[0]![3]!.signal!;
    scope.stop();
    resolveRequest(page());
    await request;
    expect(signal.aborted).toBe(true);
    expect(Object.keys(history.historyItems)).toHaveLength(0);
  });
});
