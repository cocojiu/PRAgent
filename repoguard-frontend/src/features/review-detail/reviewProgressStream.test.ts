import { afterEach, describe, expect, it, vi } from "vitest";
import { readReviewProgressStream } from "./reviewProgressStream";
import { openReviewEventStream } from "@/api/client";

vi.mock("@/api/client", () => ({ openReviewEventStream: vi.fn() }));
const open = vi.mocked(openReviewEventStream);

describe("review progress transport", () => {
  afterEach(() => { vi.resetAllMocks(); vi.useRealTimers(); });

  it("parses chunk-split CRLF events and ignores comments and invalid cursors", async () => {
    const chunks = [": hello\r", "\n\r\n", "event: progress\r\nid: 42\r", "\n\r\n",
      "event: progress\nid: injected\n\n", "event: progress\nid: 43\n\n"];
    open.mockResolvedValue(new Response(new ReadableStream({
      start(controller) {
        for (const chunk of chunks) controller.enqueue(new TextEncoder().encode(chunk));
        controller.close();
      }
    }), { headers: { "Content-Type": "text/event-stream" } }));
    const progress = vi.fn().mockResolvedValue(undefined);
    await readReviewProgressStream(9, new AbortController(), "41", progress);
    expect(progress.mock.calls).toEqual([["42"], ["43"]]);
    expect(open.mock.calls[0][2]).toBe("41");
  });

  it("rejects ordinary error pages and oversized frames", async () => {
    open.mockResolvedValueOnce(new Response("<html>error</html>"));
    await expect(readReviewProgressStream(9, new AbortController(), "", vi.fn()))
      .rejects.toThrow("unavailable");
    open.mockResolvedValueOnce(new Response("x".repeat(16_385), { headers: { "Content-Type": "text/event-stream" } }));
    await expect(readReviewProgressStream(9, new AbortController(), "", vi.fn()))
      .rejects.toThrow("budget");
  });

  it("aborts a connection with no headers within the idle budget", async () => {
    vi.useFakeTimers();
    open.mockImplementation((_id, signal) => new Promise((_resolve, reject) => {
      signal.addEventListener("abort", () => reject(new Error("aborted")));
    }));
    const controller = new AbortController();
    const result = readReviewProgressStream(9, controller, "", vi.fn()).catch(error => error);
    await vi.advanceTimersByTimeAsync(20_000);
    expect(controller.signal.aborted).toBe(true);
    expect(await result).toBeInstanceOf(Error);
  });
});
