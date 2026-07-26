import { describe, expect, it } from "vitest";
import { pruneReadNotificationIds } from "./notificationReadState";

describe("notification read state", () => {
  it("keeps only read ids that still exist in the latest notification items", () => {
    const pruned = pruneReadNotificationIds(
      new Set(["review-1", "review-2", "stale-99"]),
      ["review-1", "review-2", "review-3"]
    );

    expect(pruned).toEqual(new Set(["review-1", "review-2"]));
  });

  it("drops every persisted id when the latest response has no items", () => {
    const pruned = pruneReadNotificationIds(new Set(["stale-1", "stale-2"]), []);

    expect(pruned.size).toBe(0);
  });
});
