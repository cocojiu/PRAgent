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

  const response = (chunks: Uint8Array[], close = true, cancel = vi.fn()) => new Response(new ReadableStream({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(chunk);
      if (close) controller.close();
    },
    cancel
  }), { headers: { "Content-Type": "text/event-stream" } });

  it("accepts many valid frames coalesced into one large network chunk", async () => {
    const frames = Array.from({ length: 800 }, (_, index) => `event: progress\nid: ${index + 1}\n\n`).join("");
    expect(frames.length).toBeGreaterThan(16_384);
    open.mockResolvedValue(response([new TextEncoder().encode(frames)]));
    const progress = vi.fn().mockResolvedValue(undefined);
    await readReviewProgressStream(9, new AbortController(), "", progress);
    expect(progress.mock.calls.map(call => call[0])).toEqual(Array.from({ length: 800 }, (_, index) => String(index + 1)));
    expect(open).toHaveBeenCalledOnce();
  });

  it.each([1, 2, 7, 4096])("retains split UTF-8 and CRLF with byte slices of %i", async size => {
    const wire = new TextEncoder().encode(": 中文🙂\r\n\r\nevent: progress\r\nid: 42\r\ndata: 中文🙂\r\n\r\nevent: progress\nid: 43\n\n");
    const chunks = [];
    for (let index = 0; index < wire.length; index += size) chunks.push(wire.subarray(index, index + size));
    open.mockResolvedValue(response(chunks));
    const progress = vi.fn().mockResolvedValue(undefined);
    await readReviewProgressStream(9, new AbortController(), "41", progress);
    expect(progress.mock.calls).toEqual([["42"], ["43"]]);
    expect(open.mock.calls[0][2]).toBe("41");
  });

  it("accepts a frame exactly at the existing limit including its delimiter", async () => {
    const prefix = "event: progress\nid: 7\ndata: ";
    const frame = prefix + "x".repeat(16_384 - prefix.length - 2) + "\n\n";
    expect(frame.length).toBe(16_384);
    open.mockResolvedValue(response([new TextEncoder().encode(frame)]));
    const progress = vi.fn().mockResolvedValue(undefined);
    await readReviewProgressStream(9, new AbortController(), "", progress);
    expect(progress.mock.calls).toEqual([["7"]]);
  });

  it("rejects a complete oversized frame and cancels the body without a retry", async () => {
    const prefix = "event: progress\nid: 7\ndata: ";
    const frame = prefix + "x".repeat(16_385 - prefix.length - 2) + "\n\n";
    const cancel = vi.fn();
    open.mockResolvedValue(response([new TextEncoder().encode(frame)], false, cancel));
    const progress = vi.fn();
    await expect(readReviewProgressStream(9, new AbortController(), "", progress)).rejects.toThrow("budget");
    expect(progress).not.toHaveBeenCalled();
    expect(cancel).toHaveBeenCalledOnce();
    expect(open).toHaveBeenCalledOnce();
  });

  it("rejects an incomplete oversized frame assembled across chunks", async () => {
    const cancel = vi.fn();
    open.mockResolvedValue(response([new TextEncoder().encode("x".repeat(8192)), new TextEncoder().encode("x".repeat(8193))], false, cancel));
    await expect(readReviewProgressStream(9, new AbortController(), "", vi.fn())).rejects.toThrow("budget");
    expect(cancel).toHaveBeenCalledOnce();
  });

  it("keeps valid preceding events before rejecting the next oversized frame", async () => {
    open.mockResolvedValue(response([new TextEncoder().encode("event: progress\nid: 1\n\n" + "x".repeat(16_385) + "\n\n")]));
    const progress = vi.fn().mockResolvedValue(undefined);
    await expect(readReviewProgressStream(9, new AbortController(), "", progress)).rejects.toThrow("budget");
    expect(progress.mock.calls).toEqual([["1"]]);
  });

  it("stops further coalesced events when the caller aborts", async () => {
    open.mockResolvedValue(response([new TextEncoder().encode("event: progress\nid: 1\n\nevent: progress\nid: 2\n\n")]));
    const controller = new AbortController();
    const progress = vi.fn(async () => { controller.abort(); });
    await readReviewProgressStream(9, controller, "", progress);
    expect(progress.mock.calls).toHaveLength(1);
    expect(open).toHaveBeenCalledOnce();
  });

  it("processes a large coalesced comment chunk without growing an unfinished frame", async () => {
    const comments = ": keepalive\n\n".repeat(40_000);
    open.mockResolvedValue(response([new TextEncoder().encode(comments + "event: progress\nid: 8\n\n")]));
    const progress = vi.fn().mockResolvedValue(undefined);
    await readReviewProgressStream(9, new AbortController(), "", progress);
    expect(progress.mock.calls).toEqual([["8"]]);
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
